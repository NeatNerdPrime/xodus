package jetbrains.exodus.diskann;

import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.ints.*;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import jetbrains.exodus.diskann.collections.BoundedGreedyVertexPriorityQueue;
import jetbrains.exodus.diskann.collections.NonBlockingHashMapLongLong;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.rng.sampling.PermutationSampler;
import org.apache.commons.rng.simple.RandomSource;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class DiskANN implements AutoCloseable {
    private static final int CORES = Runtime.getRuntime().availableProcessors();
    public static final byte L2_DISTANCE = 0;
    public static final byte DOT_DISTANCE = 1;

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private static final int PAGE_SIZE_MULTIPLIER = 4 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(DiskANN.class);

    private final int vectorDim;

    private final float distanceMultiplication;

    private final int maxConnectionsPerVertex;

    private final int maxAmountOfCandidates;

    private final int pqSubVectorSize;
    private final int pqQuantizersCount;

    private long verticesSize = 0;
    private final NonBlockingHashMapLongLong graphPages = new NonBlockingHashMapLongLong(1024, false);
    private final Arena arena = Arena.openShared();
    private MemorySegment diskCache;

    private final ArrayList<ExecutorService> vectorMutationThreads = new ArrayList<>();

    /**
     * Size of vertex record in bytes.
     * <p>
     * 1. Vector data (4 bytes * vectorDim)
     * 2. Real amount of edges (1 byte)
     * 3. Edges to other vertices (4 bytes * maxConnectionsPerVertex)
     */
    private final int vertexRecordSize;

    /**
     * During calculation of the amount of vertices per page we need to take into account that first byte of
     * each page contains amount of vertices in the index.
     */
    private final int pageSize;

    private final int verticesPerPage;
    private DiskGraph diskGraph;

    private final byte distanceFunction;
    private final long diskCacheRecordVectorsOffset;
    private final long diskCacheRecordEdgesCountOffset;
    private final long diskCacheRecordEdgesOffset;

    private final long diskCacheRecordByteAlignment;

    private long pqReCalculated = 0;
    private double pqReCalculationError = 0.0;

    //1st dimension quantizer index
    //2nd index of code inside code book
    //3d dimension centroid vector
    private float[][][] pqCentroids;
    private MemorySegment pqVectors;

    private final ThreadLocal<NearestGreedySearchCachedData> nearestGreedySearchCachedDataThreadLocal;

    public DiskANN(String name, int vectorDim, byte distanceFunction) {
        this(name, vectorDim, distanceFunction, 1.2f,
                64, 128,
                32);
    }

    public DiskANN(String name, int vectorDim, byte distanceFunction,
                   float distanceMultiplication,
                   int maxConnectionsPerVertex,
                   int maxAmountOfCandidates,
                   int pqCompression) {
        this.vectorDim = vectorDim;
        this.distanceMultiplication = distanceMultiplication;
        this.maxConnectionsPerVertex = maxConnectionsPerVertex;
        this.maxAmountOfCandidates = maxAmountOfCandidates;
        this.distanceFunction = distanceFunction;

        MemoryLayout diskCacheRecordLayout = MemoryLayout.structLayout(
                MemoryLayout.sequenceLayout(vectorDim, ValueLayout.JAVA_FLOAT).withName("vector"),
                MemoryLayout.sequenceLayout(maxConnectionsPerVertex, ValueLayout.JAVA_INT).withName("edges"),
                ValueLayout.JAVA_BYTE.withName("edgesCount")
        );

        diskCacheRecordByteAlignment = diskCacheRecordLayout.byteAlignment();
        this.vertexRecordSize = (int) (
                ((diskCacheRecordLayout.byteSize() + diskCacheRecordByteAlignment - 1)
                        / diskCacheRecordByteAlignment) * diskCacheRecordByteAlignment
        );

        diskCacheRecordVectorsOffset = diskCacheRecordLayout.byteOffset(
                MemoryLayout.PathElement.groupElement("vector"));
        diskCacheRecordEdgesCountOffset = diskCacheRecordLayout.byteOffset(
                MemoryLayout.PathElement.groupElement("edgesCount"));
        diskCacheRecordEdgesOffset = diskCacheRecordLayout.byteOffset(
                MemoryLayout.PathElement.groupElement("edges"));


        if (this.vertexRecordSize > PAGE_SIZE_MULTIPLIER - 1) {
            this.pageSize = ((vertexRecordSize + PAGE_SIZE_MULTIPLIER - 1 - Integer.BYTES) /
                    (PAGE_SIZE_MULTIPLIER - Integer.BYTES)) * PAGE_SIZE_MULTIPLIER;
        } else {
            this.pageSize = PAGE_SIZE_MULTIPLIER;
        }


        this.verticesPerPage = (pageSize - Integer.BYTES) / vertexRecordSize;


        if (logger.isInfoEnabled()) {
            logger.info("Vector index " + name + " has been initialized. Vector lane count for distance calculation " +
                    "is " + SPECIES.length());
        }

        if (logger.isInfoEnabled()) {
            logger.info("Using " + CORES + " cores for processing of vectors");
        }

        for (var i = 0; i < CORES; i++) {
            var id = i;
            vectorMutationThreads.add(Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, name + "- vector mutator-" + id);
                thread.setDaemon(true);
                return thread;
            }));
        }

        pqSubVectorSize = pqCompression / Float.BYTES;
        pqQuantizersCount = this.vectorDim / pqSubVectorSize;

        if (pqCompression % Float.BYTES != 0) {
            throw new IllegalArgumentException(
                    "Vector should be divided during creation of PQ codes without remainder.");
        }

        if (vectorDim % pqSubVectorSize != 0) {
            throw new IllegalArgumentException(
                    "Vector should be divided during creation of PQ codes without remainder.");
        }

        logger.info("PQ quantizers count is " + pqQuantizersCount + ", sub vector size is " + pqSubVectorSize +
                " elements , compression is " + pqCompression + " for index '" + name + "'");
        nearestGreedySearchCachedDataThreadLocal = ThreadLocal.withInitial(() -> new NearestGreedySearchCachedData(
                new IntOpenHashSet(8 * 1024,
                        Hash.VERY_FAST_LOAD_FACTOR), new float[pqQuantizersCount * (1 << Byte.SIZE)],
                new BoundedGreedyVertexPriorityQueue(maxAmountOfCandidates)));
    }


    public void buildIndex(VectorReader vectorReader) {
        logger.info("Generating PQ codes for vectors...");
        var startPQ = System.nanoTime();
        generatePQCodes(vectorReader);
        var endPQ = System.nanoTime();
        logger.info("PQ codes for vectors have been generated. Time spent " + (endPQ - startPQ) / 1_000_000.0 +
                " ms.");

        var size = vectorReader.size();
        try (var graph = new InMemoryGraph(size)) {
            for (var i = 0; i < size; i++) {
                var vector = vectorReader.read(i);
                graph.addVector(vector);
            }


            graph.generateRandomEdges();
            var medoid = graph.medoid();

            logger.info("Search graph has been built. Pruning...");
            var startPrune = System.nanoTime();
            pruneIndex(size, graph, medoid, distanceMultiplication);
            var endPrune = System.nanoTime();
            logger.info("Search graph has been pruned. Time spent " + (endPrune - startPrune) / 1_000_000.0 + " ms.");

            graph.saveToDisk();

            diskGraph = new DiskGraph(medoid);
            verticesSize = size;
        }
    }

    public void resetPQErrorStat() {
        pqReCalculated = 0;
        pqReCalculationError = 0.0;
    }

    public double getPQErrorAvg() {
        return pqReCalculationError / pqReCalculated;
    }

    public void nearest(float[] vector, long[] result, int resultSize) {
        diskGraph.greedySearchNearest(vector, result,
                resultSize);
    }

    private void pruneIndex(int size, InMemoryGraph graph, int medoid, float distanceMultiplication) {
        var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();
        var permutation = new PermutationSampler(rng, size, size).sample();

        if (logger.isInfoEnabled()) {
            logger.info("Graph pruning started with distance multiplication " + distanceMultiplication + ".");
        }

        var mutatorFutures = new ArrayList<Future<?>>();
        var itemsPerThread = size / vectorMutationThreads.size();
        if (itemsPerThread == 0) {
            itemsPerThread = 1;
        }

        var neighborsArray = new ConcurrentLinkedQueue[size];
        for (int i = 0; i < size; i++) {
            //noinspection rawtypes
            neighborsArray[i] = new ConcurrentLinkedQueue();
        }

        var mutatorsCompleted = new AtomicInteger(0);
        var mutatorsCount = Math.min(vectorMutationThreads.size(), size);

        var mutatorsVectorIndexes = new IntArrayList[mutatorsCount];

        for (var index : permutation) {
            var mutatorId = index % mutatorsCount;

            var vertexList = mutatorsVectorIndexes[mutatorId];
            if (vertexList == null) {
                vertexList = new IntArrayList(itemsPerThread);
                mutatorsVectorIndexes[mutatorId] = vertexList;
            }
            vertexList.add(index);
        }

        for (var i = 0; i < mutatorsCount; i++) {
            var vectorIndexes = mutatorsVectorIndexes[i];
            var mutator = vectorMutationThreads.get(i);

            var mutatorId = i;
            var mutatorFuture = mutator.submit(() -> {
                var index = 0;
                while (true) {
                    @SuppressWarnings("unchecked")
                    var neighbourPairs = (ConcurrentLinkedQueue<IntIntImmutablePair>) neighborsArray[mutatorId];

                    if (!neighbourPairs.isEmpty()) {
                        var neighbourPair = neighbourPairs.poll();
                        do {
                            var vertexIndex = neighbourPair.leftInt();
                            var neighbourIndex = neighbourPair.rightInt();
                            var neighbours = graph.fetchNeighbours(vertexIndex);

                            if (!ArrayUtils.contains(neighbours, vertexIndex)) {
                                if (graph.getNeighboursSize(vertexIndex) + 1 <= maxConnectionsPerVertex) {
                                    graph.acquireVertex(vertexIndex);
                                    try {
                                        graph.appendNeighbour(vertexIndex, neighbourIndex);
                                    } finally {
                                        graph.releaseVertex(vertexIndex);
                                    }
                                } else {
                                    var neighbourSingleton = new Int2FloatOpenHashMap(1);
                                    neighbourSingleton.put(neighbourIndex, Float.NaN);
                                    graph.robustPrune(
                                            vertexIndex,
                                            neighbourSingleton,
                                            distanceMultiplication
                                    );
                                }
                            }
                            neighbourPair = neighbourPairs.poll();
                        } while (neighbourPair != null);
                    } else if (mutatorsCompleted.get() == mutatorsCount) {
                        break;
                    }

                    if (index < vectorIndexes.size()) {
                        var vectorIndex = vectorIndexes.getInt(index);
                        graph.greedySearchPrune(medoid, vectorIndex);
                        var neighbourNeighbours = graph.fetchNeighbours(vectorIndex);
                        assert vectorIndex % mutatorsCount == mutatorId;

                        for (var neighbourIndex : neighbourNeighbours) {
                            var neighbourMutatorIndex = neighbourIndex % mutatorsCount;

                            @SuppressWarnings("unchecked")
                            var neighboursList =
                                    (ConcurrentLinkedQueue<IntIntImmutablePair>) neighborsArray[neighbourMutatorIndex];
                            neighboursList.add(new IntIntImmutablePair(neighbourIndex, vectorIndex));
                        }
                        index++;
                    } else if (index == vectorIndexes.size()) {
                        index = Integer.MAX_VALUE;
                        mutatorsCompleted.incrementAndGet();
                    }
                }
                return null;
            });
            mutatorFutures.add(mutatorFuture);
        }

        for (var mutatorFuture : mutatorFutures) {
            try {
                mutatorFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        if (logger.isInfoEnabled()) {
            logger.info("Graph pruning: " + size + " vertices were processed.");
        }
    }

    private void generatePQCodes(VectorReader vectorReader) {
        pqCentroids = new float[pqQuantizersCount][][];
        var kMeans = new KMeansMiniBatchSGD[pqQuantizersCount];

        for (int i = 0; i < pqQuantizersCount; i++) {
            kMeans[i] = new KMeansMiniBatchSGD(1 << Byte.SIZE, i * pqSubVectorSize,
                    pqSubVectorSize, vectorReader);
        }

        var finishedCount = 0;

        while (finishedCount < pqQuantizersCount) {
            for (var km : kMeans) {
                var iteration = km.nextIteration(16, distanceFunction);
                if (iteration == 1_000) {
                    finishedCount++;
                }
            }
        }

        for (int i = 0; i < pqQuantizersCount; i++) {
            pqCentroids[i] = kMeans[i].centroids;
        }

        var size = vectorReader.size();
        pqVectors = arena.allocate((long) size * pqQuantizersCount);

        for (int n = 0; n < size; n++) {
            var vector = vectorReader.read(n);

            for (int i = 0; i < pqQuantizersCount; i++) {
                var centroidIndex = findClosestCentroid(distanceFunction, kMeans[i].centroids, vector, i * pqSubVectorSize);
                pqVectors.set(ValueLayout.JAVA_BYTE, (long) n * pqQuantizersCount + i, (byte) centroidIndex);
            }
        }
    }

    private float[] buildPQDistanceLookupTable(float[] vector) {
        var lookupTable = this.nearestGreedySearchCachedDataThreadLocal.get().lookupTable;

        for (int i = 0; i < pqQuantizersCount; i++) {
            var centroids = pqCentroids[i];

            for (int j = 0; j < centroids.length; j++) {
                var centroid = centroids[j];
                var distance = computeDistance(distanceFunction, centroid, vector,
                        i * pqSubVectorSize, centroid.length);
                lookupTable[i * (1 << Byte.SIZE) + j] = distance;
            }
        }

        return lookupTable;
    }

    private float computePQDistance(float[] lookupTable, int vectorIndex) {
        var distance = 0f;

        var pqIndex = pqQuantizersCount * vectorIndex;
        for (int i = pqIndex; i < pqIndex + pqQuantizersCount; i++) {
            var code = pqVectors.get(ValueLayout.JAVA_BYTE, i) & 0xFF;
            distance += lookupTable[(i - pqIndex) * (1 << Byte.SIZE) + code];
        }

        return distance;
    }

    private void computePQDistance4Batch(float[] lookupTable, int vectorIndex1, int vectorIndex2,
                                         int vectorIndex3, int vectorIndex4, float[] result) {
        assert result.length == 4;

        var pqIndex1 = pqQuantizersCount * vectorIndex1;
        var pqIndex2 = pqQuantizersCount * vectorIndex2;
        var pqIndex3 = pqQuantizersCount * vectorIndex3;
        var pqIndex4 = pqQuantizersCount * vectorIndex4;

        var result1 = 0.0f;
        var result2 = 0.0f;
        var result3 = 0.0f;
        var result4 = 0.0f;

        for (int i = 0; i < pqQuantizersCount; i++) {
            var rowOffset = i * (1 << Byte.SIZE);

            var code1 = pqVectors.get(ValueLayout.JAVA_BYTE, pqIndex1 + i) & 0xFF;
            result1 += lookupTable[rowOffset + code1];

            var code2 = pqVectors.get(ValueLayout.JAVA_BYTE, pqIndex2 + i) & 0xFF;
            result2 += lookupTable[rowOffset + code2];

            var code3 = pqVectors.get(ValueLayout.JAVA_BYTE, pqIndex3 + i) & 0xFF;
            result3 += lookupTable[rowOffset + code3];

            var code4 = pqVectors.get(ValueLayout.JAVA_BYTE, pqIndex4 + i) & 0xFF;
            result4 += lookupTable[rowOffset + code4];
        }

        result[0] = result1;
        result[1] = result2;
        result[2] = result3;
        result[3] = result4;
    }

    private void computePQDistances(float[] lookupTable,
                                    IntArrayList vertexIndexesToCheck,
                                    BoundedGreedyVertexPriorityQueue nearestCandidates,
                                    float[] distanceResult) {
        assert distanceResult.length == 4;
        assert vertexIndexesToCheck.size() <= 4;

        var elements = vertexIndexesToCheck.elements();
        var size = vertexIndexesToCheck.size();

        if (size < 4) {
            for (int i = 0; i < size; i++) {
                var vertexIndex = elements[i];
                var pqDistance = computePQDistance(lookupTable, vertexIndex);

                addPqDistance(nearestCandidates, pqDistance, vertexIndex);
            }
        } else {
            var vertexIndex1 = elements[0];
            var vertexIndex2 = elements[1];
            var vertexIndex3 = elements[2];
            var vertexIndex4 = elements[3];

            computePQDistance4Batch(lookupTable, vertexIndex1, vertexIndex2, vertexIndex3, vertexIndex4,
                    distanceResult);


            for (int i = 0; i < 4; i++) {
                var pqDistance = distanceResult[i];
                var vertexIndex = elements[i];
                addPqDistance(nearestCandidates, pqDistance, vertexIndex);
            }
        }

        vertexIndexesToCheck.clear();
    }

    private void addPqDistance(BoundedGreedyVertexPriorityQueue nearestCandidates, float pqDistance, int vertexIndex) {
        if (nearestCandidates.size() < maxAmountOfCandidates) {
            nearestCandidates.add(vertexIndex, pqDistance, true);
        } else {
            var lastVertexDistance = nearestCandidates.maxDistance();
            if (lastVertexDistance >= pqDistance) {
                nearestCandidates.add(vertexIndex, pqDistance, true);
            }
        }
    }

    static int findClosestCentroid(final byte distanceFunction, float[][] centroids, float[] vector, int from) {
        var minDistance = Float.MAX_VALUE;
        var minIndex = -1;

        for (int i = 0; i < centroids.length; i++) {
            var centroid = centroids[i];
            var distance = DiskANN.computeDistance(distanceFunction, centroid, vector, from,
                    centroid.length);

            if (distance < minDistance) {
                minDistance = distance;
                minIndex = i;
            }
        }

        return minIndex;
    }

    static void findClosestCentroid(final byte distanceFunction, float[][] centroids, float[] vector1,
                                    float[] vector2, float[] vector3, float[] vector4,
                                    int from, int[] result) {
        var minDistance_1 = Float.MAX_VALUE;
        var minDistance_2 = Float.MAX_VALUE;
        var minDistance_3 = Float.MAX_VALUE;
        var minDistance_4 = Float.MAX_VALUE;

        var minIndex_1 = -1;
        var minIndex_2 = -1;
        var minIndex_3 = -1;
        var minIndex_4 = -1;

        var distance = new float[4];

        for (int i = 0; i < centroids.length; i++) {
            var centroid = centroids[i];

            DiskANN.computeDistance(distanceFunction, centroid, 0,
                    vector1, from,
                    vector2, from,
                    vector3, from,
                    vector4, from,
                    distance,
                    centroid.length);

            if (distance[0] < minDistance_1) {
                minDistance_1 = distance[0];
                minIndex_1 = i;
            }
            if (distance[1] < minDistance_2) {
                minDistance_2 = distance[1];
                minIndex_2 = i;
            }
            if (distance[2] < minDistance_3) {
                minDistance_3 = distance[2];
                minIndex_3 = i;
            }
            if (distance[3] < minDistance_4) {
                minDistance_4 = distance[3];
                minIndex_4 = i;
            }
        }

        result[0] = minIndex_1;
        result[1] = minIndex_2;
        result[2] = minIndex_3;
        result[3] = minIndex_4;
    }

    private float computeDistance(MemorySegment firstSegment, long firstSegmentFromOffset, MemorySegment secondSegment,
                                  long secondSegmentFromOffset, int size) {
        if (distanceFunction == L2_DISTANCE) {
            return L2Distance.computeL2Distance(firstSegment, firstSegmentFromOffset, secondSegment, secondSegmentFromOffset,
                    size);
        } else if (distanceFunction == DOT_DISTANCE) {
            return computeDotDistance(firstSegment, firstSegmentFromOffset, secondSegment, secondSegmentFromOffset,
                    size);
        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }

    private void computeDistance(MemorySegment originSegment, long originSegmentOffset,
                                 MemorySegment firstSegment, long firstSegmentOffset,
                                 MemorySegment secondSegment, long secondSegmentOffset,
                                 MemorySegment thirdSegment, long thirdSegmentOffset,
                                 MemorySegment fourthSegment, long fourthSegmentOffset,
                                 int size, float[] result) {
        if (distanceFunction == L2_DISTANCE) {
            L2Distance.computeL2Distance(originSegment, originSegmentOffset,
                    firstSegment, firstSegmentOffset, secondSegment, secondSegmentOffset,
                    thirdSegment, thirdSegmentOffset, fourthSegment, fourthSegmentOffset, size, result);

        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }

    private float computeDistance(MemorySegment firstSegment, long firstSegmentFromOffset, float[] secondVector) {
        if (distanceFunction == L2_DISTANCE) {
            return L2Distance.computeL2Distance(firstSegment, firstSegmentFromOffset, secondVector, 0);
        } else if (distanceFunction == DOT_DISTANCE) {
            return computeDotDistance(firstSegment, firstSegmentFromOffset, secondVector);
        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }

    private void computeDistance(float[] originVector, @SuppressWarnings("SameParameterValue") int originVectorOffset,
                                 MemorySegment firstSegment,
                                 long firstSegmentFromOffset, MemorySegment secondSegment, long secondSegmentFromOffset,
                                 MemorySegment thirdSegment, long thirdSegmentFromOffset,
                                 MemorySegment fourthSegment, long fourthSegmentFromOffset,
                                 float[] result) {
        if (distanceFunction == L2_DISTANCE) {
            L2Distance.computeL2Distance(originVector, originVectorOffset, firstSegment, firstSegmentFromOffset,
                    secondSegment, secondSegmentFromOffset, thirdSegment, thirdSegmentFromOffset,
                    fourthSegment, fourthSegmentFromOffset, vectorDim, result);
        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }

    static float[] computeGradientStep(float[] centroid, float[] point, int pointOffset, float learningRate) {
        var result = new float[centroid.length];
        var index = 0;
        var learningRateVector = FloatVector.broadcast(SPECIES, learningRate);
        var loopBound = SPECIES.loopBound(centroid.length);
        var step = SPECIES.length();

        for (; index < loopBound; index += step) {
            var centroidVector = FloatVector.fromArray(SPECIES, centroid, index);
            var pointVector = FloatVector.fromArray(SPECIES, point, index + pointOffset);

            var diff = pointVector.sub(centroidVector);
            centroidVector = diff.fma(learningRateVector, centroidVector);
            centroidVector.intoArray(result, index);
        }

        for (; index < centroid.length; index++) {
            result[index] = centroid[index] + learningRate * (point[index + pointOffset] - centroid[index]);
        }

        return result;
    }


    static float computeDistance(final byte distanceFunction, float[] firstVector, float[] secondVector,
                                 int secondVectorFrom, int size) {
        if (distanceFunction == L2_DISTANCE) {
            return L2Distance.computeL2Distance(firstVector, 0, secondVector, secondVectorFrom, size);
        } else if (distanceFunction == DOT_DISTANCE) {
            return computeDotDistance(firstVector, secondVector, secondVectorFrom);
        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }

    static void computeDistance(final byte distanceFunction, float[] originVector,
                                @SuppressWarnings("SameParameterValue") int originVectorOffset,
                                float[] firstVector, int firstVectorOffset,
                                float[] secondVector, int secondVectorOffset,
                                float[] thirdVector, int thirdVectorOffset,
                                float[] fourthVector, int fourthVectorOffset,
                                final float[] result,
                                int size) {
        if (distanceFunction == L2_DISTANCE) {
            L2Distance.computeL2Distance(originVector, originVectorOffset, firstVector, firstVectorOffset,
                    secondVector, secondVectorOffset,
                    thirdVector, thirdVectorOffset,
                    fourthVector, fourthVectorOffset,
                    result,
                    size);
        } else {
            throw new IllegalStateException("Unknown distance function: " + distanceFunction);
        }
    }


    static float computeDotDistance(float[] firstVector, float[] secondVector, int secondVectorFrom) {
        var sumVector = FloatVector.zero(SPECIES);
        var index = 0;

        while (index < SPECIES.loopBound(firstVector.length)) {
            var first = FloatVector.fromArray(SPECIES, firstVector, index);
            var second = FloatVector.fromArray(SPECIES, secondVector, index + secondVectorFrom);

            sumVector = first.fma(second, sumVector);
            index += SPECIES.length();
        }

        var sum = sumVector.reduceLanes(VectorOperators.ADD);

        while (index < firstVector.length) {
            var mul = firstVector[index] * secondVector[index + secondVectorFrom];
            sum += mul;
            index++;
        }

        return -sum;
    }

    static float computeDotDistance(MemorySegment firstSegment, long firstSegmentFromOffset, float[] secondVector) {
        var sumVector = FloatVector.zero(SPECIES);
        var index = 0;

        while (index < SPECIES.loopBound(secondVector.length)) {
            var first = FloatVector.fromMemorySegment(SPECIES, firstSegment,
                    firstSegmentFromOffset + (long) index * Float.BYTES, ByteOrder.nativeOrder());
            var second = FloatVector.fromArray(SPECIES, secondVector, index);

            sumVector = first.fma(second, sumVector);
            index += SPECIES.length();
        }

        var sum = sumVector.reduceLanes(VectorOperators.ADD);

        for (; index < secondVector.length; index++, firstSegmentFromOffset += Float.BYTES) {
            var mul = firstSegment.get(ValueLayout.JAVA_FLOAT, firstSegmentFromOffset)
                    * secondVector[index];
            sum += mul;
        }

        return -sum;
    }

    static float computeDotDistance(MemorySegment firstSegment, long firstSegmentFromOffset,
                                    MemorySegment secondSegment,
                                    long secondSegmentFromOffset, int size) {

        var sumVector = FloatVector.zero(SPECIES);
        var index = 0;

        while (index < SPECIES.loopBound(size)) {
            var first = FloatVector.fromMemorySegment(SPECIES, firstSegment,
                    firstSegmentFromOffset + (long) index * Float.BYTES,
                    ByteOrder.nativeOrder());
            var second = FloatVector.fromMemorySegment(SPECIES, secondSegment,
                    secondSegmentFromOffset + (long) index * Float.BYTES, ByteOrder.nativeOrder());

            sumVector = first.fma(second, sumVector);
            index += SPECIES.length();
        }

        var sum = sumVector.reduceLanes(VectorOperators.ADD);

        while (index < size) {
            var mul = firstSegment.get(ValueLayout.JAVA_FLOAT,
                    firstSegmentFromOffset + (long) index * Float.BYTES)
                    * secondSegment.get(ValueLayout.JAVA_FLOAT,
                    secondSegmentFromOffset + (long) index * Float.BYTES);
            sum += mul;
            index++;
        }

        return -sum;
    }


    @Override
    public void close() {
        arena.close();
    }

    private final class InMemoryGraph implements AutoCloseable {
        private int size = 0;

        private final MemorySegment struct;
        private final long vectorsOffset;
        private final long edgesOffset;

        private final AtomicLongArray edgeVersions;

        private int medoid = -1;

        private final Arena inMemoryGraphArea;

        private InMemoryGraph(int capacity) {
            this.edgeVersions = new AtomicLongArray(capacity);
            this.inMemoryGraphArea = Arena.openShared();

            var layout = MemoryLayout.structLayout(
                    MemoryLayout.sequenceLayout((long) capacity * vectorDim, ValueLayout.JAVA_FLOAT).
                            withName("vectors"),
                    MemoryLayout.sequenceLayout((long) (maxConnectionsPerVertex + 1) * capacity,
                            ValueLayout.JAVA_INT).withName("edges")
            );
            this.struct = inMemoryGraphArea.allocate(layout);

            this.vectorsOffset = layout.byteOffset(MemoryLayout.PathElement.groupElement("vectors"));
            this.edgesOffset = layout.byteOffset(MemoryLayout.PathElement.groupElement("edges"));
        }


        private void addVector(float[] vector) {
            var index = size * vectorDim;


            MemorySegment.copy(vector, 0, struct, ValueLayout.JAVA_FLOAT,
                    vectorsOffset + (long) index * Float.BYTES,
                    vectorDim);
            size++;
            medoid = -1;
        }

        private void greedySearchPrune(
                int startVertexIndex,
                int vertexIndexToPrune) {
            var threadLocalCache = nearestGreedySearchCachedDataThreadLocal.get();
            var visitedVertexIndices = threadLocalCache.visistedVertexIndices;
            visitedVertexIndices.clear();

            var nearestCandidates = threadLocalCache.nearestCandidates;
            nearestCandidates.clear();

            var checkedVertices = new Int2FloatOpenHashMap(2 * maxAmountOfCandidates, Hash.FAST_LOAD_FACTOR);

            var startVectorOffset = vectorOffset(startVertexIndex);
            var queryVectorOffset = vectorOffset(vertexIndexToPrune);
            var dim = vectorDim;

            nearestCandidates.add(startVertexIndex, computeDistance(struct, startVectorOffset,
                    struct, queryVectorOffset, dim), false);

            var result = new float[4];
            var vectorsToCheck = new IntArrayList(4);

            while (true) {
                var notCheckedVertexPointer = nearestCandidates.nextNotCheckedVertexIndex();
                if (notCheckedVertexPointer < 0) {
                    break;
                }

                var currentVertexIndex = nearestCandidates.vertexIndex(notCheckedVertexPointer);
                assert nearestCandidates.size() <= maxAmountOfCandidates;

                checkedVertices.put(currentVertexIndex, nearestCandidates.vertexDistance(notCheckedVertexPointer));

                var vertexNeighbours = fetchNeighbours(currentVertexIndex);

                for (var vertexIndex : vertexNeighbours) {
                    if (visitedVertexIndices.add(vertexIndex)) {
                        vectorsToCheck.add(vertexIndex);
                        if (vectorsToCheck.size() == 4) {
                            var vertexIndexes = vectorsToCheck.elements();

                            var vectorOffset1 = vectorOffset(vertexIndexes[0]);
                            var vectorOffset2 = vectorOffset(vertexIndexes[1]);
                            var vectorOffset3 = vectorOffset(vertexIndexes[2]);
                            var vectorOffset4 = vectorOffset(vertexIndexes[3]);

                            computeDistance(struct, queryVectorOffset, struct, vectorOffset1,
                                    struct, vectorOffset2, struct, vectorOffset3, struct, vectorOffset4, dim,
                                    result);

                            nearestCandidates.add(vertexIndexes[0], result[0], false);
                            nearestCandidates.add(vertexIndexes[1], result[1], false);
                            nearestCandidates.add(vertexIndexes[2], result[2], false);
                            nearestCandidates.add(vertexIndexes[3], result[3], false);

                            vectorsToCheck.clear();
                        }
                    }
                }

                var size = vectorsToCheck.size();
                if (size > 0) {
                    var vertexIndexes = vectorsToCheck.elements();
                    for (int i = 0; i < size; i++) {
                        var vertexIndex = vertexIndexes[i];
                        var vectorOffset = vectorOffset(vertexIndex);

                        var distance = computeDistance(struct, queryVectorOffset, struct, vectorOffset, dim);
                        nearestCandidates.add(vertexIndex, distance, false);
                    }
                    vectorsToCheck.clear();
                }
            }

            assert nearestCandidates.size() <= maxAmountOfCandidates;
            robustPrune(vertexIndexToPrune, checkedVertices, distanceMultiplication);
        }

        private void robustPrune(
                int vertexIndex,
                Int2FloatOpenHashMap neighboursCandidates,
                float distanceMultiplication
        ) {
            var dim = vectorDim;
            acquireVertex(vertexIndex);
            try {
                Int2FloatOpenHashMap candidates;
                if (getNeighboursSize(vertexIndex) > 0) {
                    var newCandidates = neighboursCandidates.clone();
                    for (var neighbourIndex : getNeighboursAndClear(vertexIndex)) {
                        newCandidates.putIfAbsent(neighbourIndex, Float.NaN);
                    }

                    candidates = newCandidates;
                } else {
                    candidates = neighboursCandidates;
                }

                var vectorOffset = vectorOffset(vertexIndex);

                var candidatesIterator = candidates.int2FloatEntrySet().fastIterator();
                var cachedCandidates = new TreeSet<RobustPruneVertex>();

                var vectorsToCalculate = new IntArrayList(4);
                var result = new float[4];

                while (candidatesIterator.hasNext()) {
                    var entry = candidatesIterator.next();
                    var candidateIndex = entry.getIntKey();
                    var distance = entry.getFloatValue();

                    if (Float.isNaN(distance)) {
                        vectorsToCalculate.add(candidateIndex);
                        if (vectorsToCalculate.size() == 4) {
                            var vectorIndexes = vectorsToCalculate.elements();

                            var vectorOffset1 = vectorOffset(vectorIndexes[0]);
                            var vectorOffset2 = vectorOffset(vectorIndexes[1]);
                            var vectorOffset3 = vectorOffset(vectorIndexes[2]);
                            var vectorOffset4 = vectorOffset(vectorIndexes[3]);

                            computeDistance(struct, vectorOffset, struct, vectorOffset1,
                                    struct, vectorOffset2, struct, vectorOffset3, struct, vectorOffset4, dim,
                                    result);

                            cachedCandidates.add(new RobustPruneVertex(vectorIndexes[0], result[0]));
                            cachedCandidates.add(new RobustPruneVertex(vectorIndexes[1], result[1]));
                            cachedCandidates.add(new RobustPruneVertex(vectorIndexes[2], result[2]));
                            cachedCandidates.add(new RobustPruneVertex(vectorIndexes[3], result[3]));

                            vectorsToCalculate.clear();
                        }
                    } else {
                        var candidate = new RobustPruneVertex(candidateIndex, distance);
                        cachedCandidates.add(candidate);
                    }
                }

                if (vectorsToCalculate.size() > 0) {
                    var size = vectorsToCalculate.size();
                    var vectorIndexes = vectorsToCalculate.elements();
                    for (int i = 0; i < size; i++) {
                        var vectorIndex = vectorIndexes[i];

                        var vectorOff = vectorOffset(vectorIndex);
                        var distance = computeDistance(struct, vectorOffset, struct, vectorOff, dim);
                        cachedCandidates.add(new RobustPruneVertex(vectorIndex, distance));
                    }

                    vectorsToCalculate.clear();
                }

                var candidatesToCalculate = new ArrayList<RobustPruneVertex>(4);
                var removedCandidates = new ArrayList<RobustPruneVertex>(cachedCandidates.size());

                var neighbours = new IntArrayList(maxConnectionsPerVertex);
                var removed = new ArrayList<RobustPruneVertex>(cachedCandidates.size());

                var currentMultiplication = 1.0;
                neighboursLoop:
                while (currentMultiplication <= distanceMultiplication) {
                    if (!removed.isEmpty()) {
                        cachedCandidates.addAll(removed);
                        removed.clear();
                    }

                    while (!cachedCandidates.isEmpty()) {
                        var min = cachedCandidates.pollFirst();
                        assert min != null;
                        neighbours.add(min.index);

                        if (neighbours.size() == maxConnectionsPerVertex) {
                            break neighboursLoop;
                        }

                        var minIndex = vectorOffset(min.index);
                        for (RobustPruneVertex candidate : cachedCandidates) {
                            candidatesToCalculate.add(candidate);

                            assert candidatesToCalculate.size() <= 4;

                            if (candidatesToCalculate.size() == 4) {
                                var candidate1 = candidatesToCalculate.get(0);
                                var candidate2 = candidatesToCalculate.get(1);
                                var candidate3 = candidatesToCalculate.get(2);
                                var candidate4 = candidatesToCalculate.get(3);

                                var vectorOffset1 = vectorOffset(candidate1.index);
                                var vectorOffset2 = vectorOffset(candidate2.index);
                                var vectorOffset3 = vectorOffset(candidate3.index);
                                var vectorOffset4 = vectorOffset(candidate4.index);

                                computeDistance(struct, minIndex, struct, vectorOffset1,
                                        struct, vectorOffset2, struct, vectorOffset3,
                                        struct, vectorOffset4, dim, result);

                                if (result[0] * currentMultiplication <= candidate1.distance) {
                                    removedCandidates.add(candidate1);
                                }
                                if (result[1] * currentMultiplication <= candidate2.distance) {
                                    removedCandidates.add(candidate2);
                                }
                                if (result[2] * currentMultiplication <= candidate3.distance) {
                                    removedCandidates.add(candidate3);
                                }
                                if (result[3] * currentMultiplication <= candidate4.distance) {
                                    removedCandidates.add(candidate3);
                                }


                                candidatesToCalculate.clear();
                            }
                        }

                        if (candidatesToCalculate.size() > 1) {
                            for (RobustPruneVertex candidate : candidatesToCalculate) {
                                var distance = computeDistance(struct, minIndex, struct, vectorOffset(candidate.index), dim);
                                if (distance * currentMultiplication <= candidate.distance) {
                                    removedCandidates.add(candidate);
                                }
                            }
                            candidatesToCalculate.clear();
                        }

                        for (var removedCandidate : removedCandidates) {
                            cachedCandidates.remove(removedCandidate);
                        }

                        removed.addAll(removedCandidates);
                        removedCandidates.clear();
                    }

                    currentMultiplication *= 1.2;
                }

                var elements = neighbours.elements();
                var elementsSize = neighbours.size();

                ArrayUtils.reverse(elements, 0, elementsSize);

                setNeighbours(vertexIndex, elements, elementsSize);
            } finally {
                releaseVertex(vertexIndex);
            }
        }

        private long vectorOffset(int vertexIndex) {
            return (long) vertexIndex * vectorDim * Float.BYTES + vectorsOffset;
        }


        private int getNeighboursSize(int vertexIndex) {
            var version = edgeVersions.get(vertexIndex);
            while (true) {
                var size = struct.get(ValueLayout.JAVA_INT, edgesSizeOffset(vertexIndex));
                var newVersion = edgeVersions.get(vertexIndex);

                VarHandle.acquireFence();

                if (newVersion == version) {
                    assert size >= 0 && size <= maxConnectionsPerVertex;
                    return size;
                }

                version = newVersion;
            }
        }

        private long edgesSizeOffset(int vertexIndex) {
            return (long) vertexIndex * (maxConnectionsPerVertex + 1) * Integer.BYTES + edgesOffset;
        }

        @NotNull
        private int[] fetchNeighbours(int vertexIndex) {
            var version = edgeVersions.get(vertexIndex);

            while (true) {
                var edgesIndex = vertexIndex * (maxConnectionsPerVertex + 1);
                var size = struct.get(ValueLayout.JAVA_INT, (long) edgesIndex * Integer.BYTES + edgesOffset);

                var result = new int[size];
                MemorySegment.copy(struct, (long) edgesIndex * Integer.BYTES + edgesOffset + Integer.BYTES,
                        MemorySegment.ofArray(result), 0L, (long) size * Integer.BYTES);
                var newVersion = edgeVersions.get(vertexIndex);

                VarHandle.acquireFence();
                if (newVersion == version) {
                    assert size <= maxConnectionsPerVertex;
                    return result;
                }

                version = newVersion;
            }
        }

        private void setNeighbours(int vertexIndex, int[] neighbours, int size) {
            validateLocked(vertexIndex);
            assert (size >= 0 && size <= maxConnectionsPerVertex);

            var edgesOffset = ((long) vertexIndex * (maxConnectionsPerVertex + 1)) * Integer.BYTES + this.edgesOffset;
            struct.set(ValueLayout.JAVA_INT, edgesOffset, size);

            MemorySegment.copy(MemorySegment.ofArray(neighbours), 0L, struct,
                    edgesOffset + Integer.BYTES,
                    (long) size * Integer.BYTES);
        }

        private void appendNeighbour(int vertexIndex, int neighbour) {
            validateLocked(vertexIndex);

            var edgesOffset = ((long) vertexIndex * (maxConnectionsPerVertex + 1)) * Integer.BYTES + this.edgesOffset;
            var size = struct.get(ValueLayout.JAVA_INT, edgesOffset);

            assert size + 1 <= maxConnectionsPerVertex;

            struct.set(ValueLayout.JAVA_INT, edgesOffset, size + 1);
            struct.set(ValueLayout.JAVA_INT, edgesOffset + (long) (size + 1) * Integer.BYTES, neighbour);
        }


        private void generateRandomEdges() {
            if (size == 1) {
                return;
            }

            var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();
            var shuffledIndexes = PermutationSampler.natural(size);
            PermutationSampler.shuffle(rng, shuffledIndexes);

            var maxEdges = Math.min(size - 1, maxConnectionsPerVertex);
            var shuffleIndex = 0;
            for (var i = 0; i < size; i++) {
                var edgesOffset = edgesSizeOffset(i);
                struct.set(ValueLayout.JAVA_INT, edgesOffset, maxEdges);

                var addedEdges = 0;
                while (addedEdges < maxEdges) {
                    var randomIndex = shuffledIndexes[shuffleIndex];
                    shuffleIndex++;

                    if (shuffleIndex == size) {
                        PermutationSampler.shuffle(rng, shuffledIndexes);
                        shuffleIndex = 0;
                    } else if (randomIndex == i) {
                        continue;
                    }

                    struct.set(ValueLayout.JAVA_INT, edgesOffset + Integer.BYTES, randomIndex);
                    edgesOffset += Integer.BYTES;
                    addedEdges++;
                }
            }
        }

        private int[] getNeighboursAndClear(int vertexIndex) {
            validateLocked(vertexIndex);
            var edgesOffset = ((long) vertexIndex * (maxConnectionsPerVertex + 1)) * Integer.BYTES + this.edgesOffset;
            var result = fetchNeighbours(vertexIndex);

            edgeVersions.incrementAndGet(vertexIndex);
            struct.set(ValueLayout.JAVA_INT, edgesOffset, 0);
            edgeVersions.incrementAndGet(vertexIndex);

            return result;
        }

        private int medoid() {
            if (medoid == -1L) {
                medoid = calculateMedoid();
            }

            return medoid;
        }

        private void acquireVertex(long vertexIndex) {
            while (true) {
                var version = edgeVersions.get((int) vertexIndex);
                if ((version & 1L) != 0L) {
                    throw new IllegalStateException("Vertex $vertexIndex is already acquired");
                }
                if (edgeVersions.compareAndSet((int) vertexIndex, version, version + 1)) {
                    return;
                }
            }
        }

        private void validateLocked(long vertexIndex) {
            var version = edgeVersions.get((int) vertexIndex);
            if ((version & 1L) != 1L) {
                throw new IllegalStateException("Vertex $vertexIndex is not acquired");
            }
        }

        private void releaseVertex(long vertexIndex) {
            while (true) {
                var version = edgeVersions.get((int) vertexIndex);
                if ((version & 1L) != 1L) {
                    throw new IllegalStateException("Vertex $vertexIndex is not acquired");
                }
                if (edgeVersions.compareAndSet((int) vertexIndex, version, version + 1)) {
                    return;
                }
            }
        }

        private int calculateMedoid() {
            if (size == 1) {
                return 0;
            }

            var meanVector = new float[vectorDim];

            for (var i = 0; i < size; i++) {
                var vectorOffset = vectorOffset(i);
                for (var j = 0; j < vectorDim; j++) {
                    meanVector[j] += struct.get(ValueLayout.JAVA_FLOAT, vectorOffset + (long) j * Float.BYTES);
                }
            }

            for (var j = 0; j < vectorDim; j++) {
                meanVector[j] = meanVector[j] / size;
            }

            var minDistance = Double.POSITIVE_INFINITY;
            var medoidIndex = -1;

            for (var i = 0; i < size; i++) {
                var distance = computeDistance(struct, (long) i * vectorDim, meanVector);

                if (distance < minDistance) {
                    minDistance = distance;
                    medoidIndex = i;
                }
            }

            return medoidIndex;
        }


        private void saveToDisk() {
            var pagesToWrite = (size + verticesPerPage - 1 / verticesPerPage);
            diskCache = arena.allocate((long) pagesToWrite * pageSize,
                    diskCacheRecordByteAlignment);

            var verticesPerPage = pageSize / vertexRecordSize;

            var wittenVertices = 0;

            var vectorsIndex = 0;
            var pageSegmentOffset = 0;

            while (wittenVertices < size) {
                diskCache.set(ValueLayout.JAVA_INT, pageSegmentOffset, size);

                var verticesToWrite = Math.min(verticesPerPage, size - wittenVertices);
                for (var i = 0; i < verticesToWrite; i++) {
                    var recordOffset = (long) i * vertexRecordSize + Integer.BYTES + pageSegmentOffset;

                    var edgesIndex = wittenVertices * (maxConnectionsPerVertex + 1);

                    for (var j = 0; j < vectorDim; j++) {
                        var vectorItem = struct.get(ValueLayout.JAVA_FLOAT, vectorsOffset +
                                (long) vectorsIndex * Float.BYTES);
                        diskCache.set(ValueLayout.JAVA_FLOAT,
                                recordOffset + diskCacheRecordVectorsOffset +
                                        (long) j * Float.BYTES, vectorItem);
                        vectorsIndex++;
                    }

                    var edgesSize = struct.get(ValueLayout.JAVA_INT,
                            (long) edgesIndex * Integer.BYTES + edgesOffset);
                    assert (edgesSize >= 0 && edgesSize <= maxConnectionsPerVertex);
                    edgesIndex++;

                    for (var j = 0; j < edgesSize; j++) {
                        var edgesOffset = (long) edgesIndex * Integer.BYTES + this.edgesOffset;
                        var neighbourIndex = struct.get(ValueLayout.JAVA_INT, edgesOffset);
                        diskCache.set(ValueLayout.JAVA_INT,
                                recordOffset + diskCacheRecordEdgesOffset + (long) j * Integer.BYTES, neighbourIndex);
                        edgesIndex++;
                    }
                    diskCache.set(ValueLayout.JAVA_BYTE,
                            recordOffset + diskCacheRecordEdgesCountOffset, (byte) edgesSize);
                    wittenVertices++;
                }

                graphPages.put(pageSegmentOffset / pageSize, pageSegmentOffset);
                pageSegmentOffset += pageSize;
            }
        }

        @Override
        public void close() {
            inMemoryGraphArea.close();
        }
    }

    private final class DiskGraph {
        private final long medoid;

        private DiskGraph(long medoid) {
            this.medoid = medoid;
        }

        private void greedySearchNearest(
                float[] queryVector,
                long[] result,
                int k
        ) {
            var threadLocalCache = nearestGreedySearchCachedDataThreadLocal.get();

            var visitedVertexIndices = threadLocalCache.visistedVertexIndices;
            visitedVertexIndices.clear();

            var nearestCandidates = threadLocalCache.nearestCandidates;
            nearestCandidates.clear();

            var startVertexIndex = medoid;
            var startVectorOffset = vectorOffset(startVertexIndex);

            var distanceResult = threadLocalCache.distanceResult;
            var vertexIndexesToCheck = threadLocalCache.vertexIndexesToCheck;
            vertexIndexesToCheck.clear();

            nearestCandidates.add((int) startVertexIndex, computeDistance(diskCache, startVectorOffset, queryVector), false);

            assert nearestCandidates.size() <= maxAmountOfCandidates;
            visitedVertexIndices.add((int) startVertexIndex);

            float[] lookupTable = null;

            while (true) {
                int currentVertex = -1;

                vertexRecalculationLoop:
                while (true) {
                    vertexIndexesToCheck.clear();

                    while (vertexIndexesToCheck.size() < 4) {
                        var notCheckedVertex = nearestCandidates.nextNotCheckedVertexIndex();

                        if (notCheckedVertex < 0) {
                            if (vertexIndexesToCheck.isEmpty()) {
                                break vertexRecalculationLoop;
                            }

                            recalculateDistances(queryVector, nearestCandidates,
                                    vertexIndexesToCheck, distanceResult);
                            continue;
                        }

                        if (nearestCandidates.isPqDistance(notCheckedVertex)) {
                            vertexIndexesToCheck.add(notCheckedVertex);
                            assert vertexIndexesToCheck.size() <= 4;
                        } else {
                            if (!vertexIndexesToCheck.isEmpty()) {
                                recalculateDistances(queryVector, nearestCandidates,
                                        vertexIndexesToCheck, distanceResult);
                                continue;
                            }
                            currentVertex = nearestCandidates.vertexIndex(notCheckedVertex);
                            break vertexRecalculationLoop;
                        }
                    }
                    recalculateDistances(queryVector, nearestCandidates,
                            vertexIndexesToCheck, distanceResult);
                }


                if (currentVertex < 0) {
                    break;
                }

                var recordOffset = recordOffset(currentVertex);
                var neighboursSizeOffset = recordOffset + diskCacheRecordEdgesCountOffset;
                var neighboursCount = Byte.toUnsignedInt(diskCache.get(ValueLayout.JAVA_BYTE, neighboursSizeOffset));
                var neighboursEnd = neighboursCount * Integer.BYTES + diskCacheRecordEdgesOffset + recordOffset;


                assert vertexIndexesToCheck.isEmpty();
                for (var neighboursOffset = recordOffset + diskCacheRecordEdgesOffset;
                     neighboursOffset < neighboursEnd; neighboursOffset += Integer.BYTES) {
                    var vertexIndex = diskCache.get(ValueLayout.JAVA_INT, neighboursOffset);

                    if (visitedVertexIndices.add(vertexIndex)) {
                        if (lookupTable == null) {
                            lookupTable = buildPQDistanceLookupTable(queryVector);
                        }

                        assert vertexIndexesToCheck.size() <= 4;

                        vertexIndexesToCheck.add(vertexIndex);
                        if (vertexIndexesToCheck.size() == 4) {
                            computePQDistances(lookupTable, vertexIndexesToCheck, nearestCandidates,
                                    distanceResult);
                        }

                        assert vertexIndexesToCheck.size() <= 4;
                    }
                }

                assert vertexIndexesToCheck.size() <= 4;

                if (!vertexIndexesToCheck.isEmpty()) {
                    computePQDistances(lookupTable, vertexIndexesToCheck, nearestCandidates,
                            distanceResult);
                }

                assert vertexIndexesToCheck.isEmpty();
                assert nearestCandidates.size() <= maxAmountOfCandidates;
            }

            nearestCandidates.vertexIndices(result, k);
        }

        private void recalculateDistances(float[] queryVector, BoundedGreedyVertexPriorityQueue nearestCandidates,
                                          IntArrayList vertexIndexesToCheck, float[] distanceResult) {
            var elements = vertexIndexesToCheck.elements();
            var size = vertexIndexesToCheck.size();

            if (size < 4) {
                for (int i = 0; i < size; i++) {
                    var notCheckedVertex = elements[i];

                    var vertexIndex = nearestCandidates.vertexIndex(notCheckedVertex);
                    var preciseDistance = computeDistance(diskCache, vectorOffset(vertexIndex),
                            queryVector);
                    var pqDistance = nearestCandidates.vertexDistance(notCheckedVertex);
                    var newVertexIndex = nearestCandidates.resortVertex(notCheckedVertex, preciseDistance);

                    for (int k = i + 1; k < size; k++) {
                        elements[k] = elements[k] - ((elements[k] - newVertexIndex - 1) >>> (Integer.SIZE - 1));
                    }

                    if (preciseDistance != 0) {
                        pqReCalculated++;
                        pqReCalculationError += 100.0 * Math.abs(preciseDistance - pqDistance) / preciseDistance;
                    }
                }
            } else {
                var notCheckedVertex1 = elements[0];
                var notCheckedVertex2 = elements[1];
                var notCheckedVertex3 = elements[2];
                var notCheckedVertex4 = elements[3];

                var vertexIndex1 = nearestCandidates.vertexIndex(notCheckedVertex1);
                var vertexIndex2 = nearestCandidates.vertexIndex(notCheckedVertex2);
                var vertexIndex3 = nearestCandidates.vertexIndex(notCheckedVertex3);
                var vertexIndex4 = nearestCandidates.vertexIndex(notCheckedVertex4);

                assert notCheckedVertex1 < notCheckedVertex2;
                assert notCheckedVertex2 < notCheckedVertex3;
                assert notCheckedVertex3 < notCheckedVertex4;

                var vectorOffset1 = vectorOffset(vertexIndex1);
                var vectorOffset2 = vectorOffset(vertexIndex2);
                var vectorOffset3 = vectorOffset(vertexIndex3);
                var vectorOffset4 = vectorOffset(vertexIndex4);

                var pqDistance1 = nearestCandidates.vertexDistance(notCheckedVertex1);
                var pqDistance2 = nearestCandidates.vertexDistance(notCheckedVertex2);
                var pqDistance3 = nearestCandidates.vertexDistance(notCheckedVertex3);
                var pqDistance4 = nearestCandidates.vertexDistance(notCheckedVertex4);

                computeDistance(queryVector, 0, diskCache, vectorOffset1,
                        diskCache, vectorOffset2, diskCache, vectorOffset3, diskCache, vectorOffset4,
                        distanceResult);

                //preventing branch miss predictions using bit shift and subtraction
                var newVertexIndex1 = nearestCandidates.resortVertex(notCheckedVertex1, distanceResult[0]);
                assert vertexIndex1 == nearestCandidates.vertexIndex(newVertexIndex1);

                //if newVertexIndex1 >= notCheckedVertex1 then -1 else 0, the same logic
                //is applied for the rest follow-up indexes
                notCheckedVertex2 = notCheckedVertex2 -
                        ((notCheckedVertex2 - newVertexIndex1 - 1) >>> (Integer.SIZE - 1));
                notCheckedVertex3 = notCheckedVertex3 -
                        ((notCheckedVertex3 - newVertexIndex1 - 1) >>> (Integer.SIZE - 1));
                notCheckedVertex4 = notCheckedVertex4 -
                        ((notCheckedVertex4 - newVertexIndex1 - 1) >>> (Integer.SIZE - 1));
                assert vertexIndex2 == nearestCandidates.vertexIndex(notCheckedVertex2);
                assert vertexIndex3 == nearestCandidates.vertexIndex(notCheckedVertex3);
                assert vertexIndex4 == nearestCandidates.vertexIndex(notCheckedVertex4);

                var newVertexIndex2 = nearestCandidates.resortVertex(notCheckedVertex2, distanceResult[1]);
                assert vertexIndex2 == nearestCandidates.vertexIndex(newVertexIndex2);

                notCheckedVertex3 = notCheckedVertex3 - ((notCheckedVertex3 - newVertexIndex2 - 1) >>> (Integer.SIZE - 1));
                notCheckedVertex4 = notCheckedVertex4 - ((notCheckedVertex4 - newVertexIndex2 - 1) >>> (Integer.SIZE - 1));
                assert vertexIndex3 == nearestCandidates.vertexIndex(notCheckedVertex3);
                assert vertexIndex4 == nearestCandidates.vertexIndex(notCheckedVertex4);

                var newVertexIndex3 = nearestCandidates.resortVertex(notCheckedVertex3, distanceResult[2]);
                assert vertexIndex3 == nearestCandidates.vertexIndex(newVertexIndex3);

                notCheckedVertex4 = notCheckedVertex4 - ((notCheckedVertex4 - newVertexIndex3 - 1)
                        >>> (Integer.SIZE - 1));
                assert vertexIndex4 == nearestCandidates.vertexIndex(notCheckedVertex4);

                nearestCandidates.resortVertex(notCheckedVertex4, distanceResult[3]);

                if (distanceResult[0] != 0) {
                    pqReCalculated++;
                    pqReCalculationError += 100.0 * Math.abs(distanceResult[0] - pqDistance1) / distanceResult[0];
                }

                if (distanceResult[1] != 0) {
                    pqReCalculated++;
                    pqReCalculationError += 100.0 * Math.abs(distanceResult[1] - pqDistance2) / distanceResult[1];
                }

                if (distanceResult[2] != 0) {
                    pqReCalculated++;
                    pqReCalculationError += 100.0 * Math.abs(distanceResult[2] - pqDistance3) / distanceResult[2];
                }

                if (distanceResult[3] != 0) {
                    pqReCalculated++;
                    pqReCalculationError += 100.0 * Math.abs(distanceResult[3] - pqDistance4) / distanceResult[3];
                }
            }

            vertexIndexesToCheck.clear();
        }

        private long vectorOffset(long vertexIndex) {
            return recordOffset(vertexIndex) + diskCacheRecordVectorsOffset;
        }

        private long recordOffset(long vertexIndex) {
            if (vertexIndex >= verticesSize) {
                throw new IllegalArgumentException();
            }

            var vertexPageIndex = vertexIndex / verticesPerPage;
            var vertexPageOffset = graphPages.get(vertexPageIndex);
            var vertexOffset = (vertexIndex % verticesPerPage) * vertexRecordSize + Integer.BYTES;
            return vertexPageOffset + vertexOffset;
        }

    }

    private static final class NearestGreedySearchCachedData {
        private final IntOpenHashSet visistedVertexIndices;
        private final float[] lookupTable;

        private final BoundedGreedyVertexPriorityQueue nearestCandidates;

        private final float[] distanceResult;

        private final IntArrayList vertexIndexesToCheck = new IntArrayList();


        private NearestGreedySearchCachedData(IntOpenHashSet vertexIndices, float[] lookupTable, BoundedGreedyVertexPriorityQueue nearestCandidates) {
            this.visistedVertexIndices = vertexIndices;
            this.lookupTable = lookupTable;
            this.nearestCandidates = nearestCandidates;
            this.distanceResult = new float[4];
        }
    }
}
