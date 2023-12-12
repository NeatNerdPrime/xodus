/*
 * Copyright ${inceptionYear} - ${year} ${owner}
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.vectoriadb.index;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Ignore the compressionRation problem. Use additional space, you will fix (maybe) this problem later when everything works.
 * Do not use this class with L2DistanceFunction, it will not calculate distances properly.
 * This class works only with DotDistanceFunction.
 */
class NormExplicitQuantizer extends AbstractQuantizer {

    private final Arena arena;

    private final int numWorkers;
    private final int kMeansMaxIteration = 50;

    // used for the inner product calculation, needed at the search time
    private final L2PQQuantizer normalizedL2 = new L2PQQuantizer();

    private final int normCodebooksCount;
    // how many values a single codebook should encode
    private int codeBaseSize;
    private FloatVectorSegment[] codebooks;
    private CodeSegment[] codes;

    public NormExplicitQuantizer() {
        this(1);
    }

    NormExplicitQuantizer(int normCodebooksCount) {
        if (normCodebooksCount < 1) throw new IllegalArgumentException("normCodebooksCount should be greater or equal 1");
        arena = Arena.ofShared();
        this.normCodebooksCount = normCodebooksCount;
        numWorkers = ParallelExecution.availableCores();
    }

    @Override
    public void generatePQCodes(int compressionRatio, VectorReader vectorReader, @NotNull ProgressTracker progressTracker) {
        progressTracker.pushPhase("Norm-explicit PQ codes creation", "norm quantizers count", String.valueOf(normCodebooksCount));

        try (
                var localArena = Arena.ofShared();
                var normalizedVectorReader = new NormalizedVectorReader(vectorReader);
                var pBuddy = new ParallelBuddy(numWorkers, "norm-explicit-pq-calc-")
        ) {
            codeBaseSize = Math.min(CODE_BASE_SIZE, vectorReader.size());

            codebooks = FloatVectorSegment.makeNativeSegments(arena, normCodebooksCount, codeBaseSize, 1); // norm has a single dimension
            codes = ByteCodeSegment.makeNativeSegments(arena, normCodebooksCount, vectorReader.size());

            // precalculate original vector norms not to calculate them on every read
            normalizedVectorReader.precalculateOriginalVectorNorms(pBuddy, progressTracker);

            normalizedL2.generatePQCodes(compressionRatio, normalizedVectorReader, progressTracker);

            // Calculate relativeNorm = originalVectorNorm / normalizedApproximatedVectorNorm, so later we can get originalVectorNorm = relativeNorm * normalizedApproximatedVectorNorm
            var relativeNorms = FloatVectorSegment.makeNativeSegment(localArena, vectorReader.size(), 1);
            calculateRelativeNorms(pBuddy, vectorReader, normalizedVectorReader, relativeNorms, progressTracker);

            trainCodebook(pBuddy, relativeNorms, 0, progressTracker);

            if (normCodebooksCount > 1) {
                var normResiduals = FloatVectorSegment.makeNativeSegment(localArena, vectorReader.size(), 1);
                for (int codebookIdx = 1; codebookIdx < normCodebooksCount; codebookIdx++) {
                    calculateNormResiduals(pBuddy, relativeNorms, normResiduals, codebookIdx, progressTracker);
                    trainCodebook(pBuddy, normResiduals, codebookIdx, progressTracker);
                }
            }

        } finally {
            progressTracker.pullPhase();
        }
    }

    private void trainCodebook(ParallelBuddy pBuddy, FloatVectorSegment norms, int codebookIdx, ProgressTracker progressTracker) {
        var kmeans = new KMeansClustering(
                L2DistanceFunction.INSTANCE,
                new FloatVectorSegmentReader(norms),
                codebooks[codebookIdx],
                codes[codebookIdx],
                kMeansMaxIteration,
                pBuddy
        );
        kmeans.calculateCentroids(progressTracker);
    }

    private void calculateRelativeNorms(ParallelBuddy pBuddy, VectorReader vectorReader, NormalizedVectorReader normalizedVectorReader, FloatVectorSegment relativeNorms, ProgressTracker progressTracker) {
        pBuddy.run(
                "Calculate relative norms",
                vectorReader.size(),
                progressTracker,
                (_, vectorIdx) -> {
                    var normalizedVectorApproximation = normalizedL2.getVectorApproximation(vectorIdx);
                    var normalizedVectorApproximationNorm = VectorOperations.calculateL2Norm(normalizedVectorApproximation);
                    var originalVectorNorm = normalizedVectorReader.getOriginalVectorNorm(vectorIdx);
                    relativeNorms.set(vectorIdx, 0, originalVectorNorm / normalizedVectorApproximationNorm);
                }
        );
    }

    private void calculateNormResiduals(ParallelBuddy pBuddy, FloatVectorSegment relativeNorms, FloatVectorSegment normResiduals, int codebookIdx, ProgressTracker progressTracker) {
        pBuddy.run(
                "Calculate norm residuals " + codebookIdx,
                relativeNorms.count(),
                progressTracker,
                (_, vectorIdx) -> {
                    var relativeNorm = relativeNorms.get(vectorIdx, 0);
                    var relativeNormApproximation = 0f;
                    for (int i = 0; i < codebookIdx; i++) {
                        var code = codes[i].get(vectorIdx);
                        relativeNormApproximation += codebooks[i].get(code, 0);
                    }
                    normResiduals.set(vectorIdx, 0, relativeNorm - relativeNormApproximation);
                }
        );
    }

    public float[] getVectorApproximation(int vectorIdx) {
        var norm = getRelativeNormApproximation(vectorIdx);

        var normalizedVectorApproximation = normalizedL2.getVectorApproximation(vectorIdx);
        var segment = MemorySegment.ofArray(normalizedVectorApproximation);
        VectorOperations.mul(segment, 0, norm, segment, 0, normalizedVectorApproximation.length);
        return normalizedVectorApproximation;
    }

    private float getRelativeNormApproximation(int vectorIdx) {
        var norm = 0f;
        for (int codeIdx = 0; codeIdx < normCodebooksCount; codeIdx++) {
            var code = codes[codeIdx].get(vectorIdx);
            norm += codebooks[codeIdx].get(code, 0);
        }
        return norm;
    }


    // Calculate distances using the lookup table

    @Override
    public float[] blankLookupTable() {
        // It is used when initializing a thread local NearestGreedySearchCachedData in the IndexReader constructor.
        // It is filled in IndexReader.nearest() when doing the search
        return normalizedL2.blankLookupTable();
    }

    @Override
    public void buildLookupTable(float[] vector, float[] lookupTable, DistanceFunction distanceFunction) {
        // It is used in IndexReader.nearest() when doing the search.
        normalizedL2.buildLookupTable(vector, lookupTable, distanceFunction);
    }

    @Override
    public float computeDistanceUsingLookupTable(float[] lookupTable, int vectorIndex) {
        // It is used in IndexReader.nearest() when doing the search.

        var distance = normalizedL2.computeDistanceUsingLookupTable(lookupTable, vectorIndex);
        var norm = getRelativeNormApproximation(vectorIndex);
        return distance * norm;
    }

    @Override
    public void computeDistance4BatchUsingLookupTable(float[] lookupTable, int vectorIndex1, int vectorIndex2, int vectorIndex3, int vectorIndex4, float[] result) {
        // It is used in IndexReader.nearest() when doing the search.

        normalizedL2.computeDistance4BatchUsingLookupTable(lookupTable, vectorIndex1, vectorIndex2, vectorIndex3, vectorIndex4, result);
        result[0] *= getRelativeNormApproximation(vectorIndex1);
        result[1] *= getRelativeNormApproximation(vectorIndex2);
        result[2] *= getRelativeNormApproximation(vectorIndex3);
        result[3] *= getRelativeNormApproximation(vectorIndex4);
    }


    // PQ k-means clustering

    @Override
    public VectorsByPartitions splitVectorsByPartitions(VectorReader vectorReader, int numClusters, int iterations, DistanceFunction distanceFunction, @NotNull ProgressTracker progressTracker) {
        // It is used in IndexBuilder.buildIndex() to split the vectors into partitions to build graph indices for every partition separately.
        // The whole graph index does not fit memory, so we have to split vectors into partitions.

        // We cannot use PQ k-means clustering here as it does calculations using distance tables for the cluster centroids.
        // We have only normalized centroids (because they are built on normalized vectors), so we can do PQ k-means clustering
        // that is ok only for normalized vectors.
        // Results of such clustering will be less accurate than clustering of the original vectors.

        // So we do a regular k-means clustering on the original vectors.

        try (
                var pBuddy = new ParallelBuddy(numWorkers, "kmeans-clustering");
                var arena = Arena.ofShared()
        ) {
            var centroids = FloatVectorSegment.makeNativeSegment(arena, numClusters, vectorReader.dimensions());
            var centroidIdxByVectorIdx = ByteCodeSegment.makeNativeSegment(arena, vectorReader.size());
            var kmeans = new KMeansClustering(
                    distanceFunction,
                    vectorReader,
                    centroids,
                    centroidIdxByVectorIdx,
                    iterations,
                    pBuddy
            );
            kmeans.calculateCentroids(progressTracker);

            // prepare result storage for every worker
            var resultPerWorker = new IntArrayList[numWorkers][];
            var averageCapacityPerWorker = vectorReader.size() / numClusters / numWorkers * 2;
            for (int workerIdx = 0; workerIdx < numWorkers; workerIdx++) {
                resultPerWorker[workerIdx] = new IntArrayList[numClusters];
                for (int centroidIdx = 0; centroidIdx < numClusters; centroidIdx++) {
                    resultPerWorker[workerIdx][centroidIdx] = new IntArrayList(averageCapacityPerWorker);
                }
            }

            pBuddy.run(
                    "Calculate two closest centroids for each vector",
                    vectorReader.size(),
                    progressTracker,
                    (workerIdx, vectorIdx) -> {
                        var twoCentroidIndices = findTwoClosestClusters(vectorReader.read(vectorIdx), centroids, distanceFunction);
                        var centroidIdx1 = (int) (twoCentroidIndices >>> 32);
                        var centroidIdx2 = (int) twoCentroidIndices;
                        resultPerWorker[workerIdx][centroidIdx1].add(vectorIdx);
                        resultPerWorker[workerIdx][centroidIdx2].add(vectorIdx);
                    }
            );

            // assemble the result
            var result = new IntArrayList[numClusters];
            var averageResultCapacity = vectorReader.size() / numClusters * 2;
            for (int centroidIdx = 0; centroidIdx < numClusters; centroidIdx++) {
                result[centroidIdx] = new IntArrayList(averageResultCapacity);
                for (int workerIdx = 0; workerIdx < numWorkers; workerIdx++) {
                    result[centroidIdx].addElements(result[centroidIdx].size(), resultPerWorker[workerIdx][centroidIdx].elements());
                }
            }
            return new VectorsByPartitions(centroids.toArray(), result);
        }
    }

    private long findTwoClosestClusters(MemorySegment vector, FloatVectorSegment centroids, DistanceFunction distanceFun) {
        int minIndex1 = -1;
        int minIndex2 = -1;
        float minDistance1 = Float.MAX_VALUE;
        float minDistance2 = Float.MAX_VALUE;

        var numClusters = centroids.count();
        var dimensions = centroids.dimensions();

        for (int i = 0; i < numClusters; i++) {
            var centroid = centroids.get(i);

            var distance = distanceFun.computeDistance(vector, 0, centroid, 0, dimensions);
            if (distance < minDistance1) {
                minDistance2 = minDistance1;
                minDistance1 = distance;

                minIndex2 = minIndex1;
                minIndex1 = i;
            } else if (distance < minDistance2) {
                minDistance2 = distance;
                minIndex2 = i;
            }
        }

        return (((long) minIndex1) << 32) | minIndex2;
    }

    @Override
    public float[][] calculateCentroids(int clustersCount, int iterations, DistanceFunction distanceFunction, @NotNull ProgressTracker progressTracker) {
        // It is used in IndexBuilder.buildIndex() to calculate the graph medoid to start the search from.
        // And also in tests to test the quality of k-means clustering

        // Use an extra L2 quantazier that is trained on the original vectors.
        // Delegate the job to it.

        return new float[0][];
    }


    // Store/load/close

    @Override
    public void load(DataInputStream dataInputStream) throws IOException {
        // 1. Delegate to the L2
        // 2. Load the local state of this instance
    }

    @Override
    public void store(DataOutputStream dataOutputStream) throws IOException {
        // 1. Delegate to the L2
        // 2. Store the local state of this instance
    }

    @Override
    public void close() {
        // do the same as L2 quantizer
    }
}
