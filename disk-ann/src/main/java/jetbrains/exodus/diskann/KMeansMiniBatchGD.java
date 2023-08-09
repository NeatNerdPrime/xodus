/*
 * Copyright 2010 - 2023 JetBrains s.r.o.
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
package jetbrains.exodus.diskann;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.rng.sampling.PermutationSampler;
import org.apache.commons.rng.simple.RandomSource;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.BitSet;

final class KMeansMiniBatchGD {
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    final float[] centroids;
    final int k;
    final VectorReader vectorReader;
    final int subVecOffset;

    final int[] centroidsSamplesCount;

    private int currentIndex;
    private final int iterations;
    private final int[] clusterIndexesPerVector;

    private final int subVecSize;

    private final int id;

    KMeansMiniBatchGD(int id, int k, int iterations, int subVecOffset, int subVecSize, VectorReader vectorReader) {
        this.id = id;
        this.vectorReader = vectorReader;
        this.subVecOffset = subVecOffset;
        this.centroidsSamplesCount = new int[k];
        this.iterations = iterations;
        this.subVecSize = subVecSize;


        var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();

        var size = vectorReader.size();
        clusterIndexesPerVector = new int[size];
        Arrays.fill(clusterIndexesPerVector, -1);

        if (size <= k) {
            this.k = size;
            centroids = new float[size * subVecSize];
            for (int i = 0; i < size; i++) {
                var vector = vectorReader.read(i);
                MemorySegment.copy(vector, ValueLayout.JAVA_FLOAT_UNALIGNED, (long) subVecOffset * Float.BYTES,
                        centroids, i * subVecSize, subVecSize);
            }
        } else if (size < 4 * k) {
            this.k = k;
            centroids = new float[k * subVecSize];
            var indexes = new int[size];

            for (int i = 0; i < size; i++) {
                indexes[i] = i;
            }

            ArrayUtils.shuffle(indexes);

            for (int i = 0; i < k; i++) {
                var centroidIndex = indexes[i];
                var vector = vectorReader.read(centroidIndex);
                MemorySegment.copy(vector, ValueLayout.JAVA_FLOAT_UNALIGNED, (long) subVecOffset * Float.BYTES,
                        centroids, i * subVecSize, subVecSize);
            }
        } else {
            this.k = k;
            centroids = new float[k * subVecSize];
            var bitSet = new BitSet(size);
            for (int i = 0; i < k; i++) {
                int centroidIndex;
                do {
                    centroidIndex = rng.nextInt(size);
                } while (bitSet.get(centroidIndex));
                bitSet.set(centroidIndex);

                var vector = vectorReader.read(centroidIndex);
                MemorySegment.copy(vector, ValueLayout.JAVA_FLOAT_UNALIGNED, (long) subVecOffset * Float.BYTES,
                        centroids, i * subVecSize, subVecSize);
            }
        }
    }

    boolean nextBatch(@SuppressWarnings("SameParameterValue") int minBatchSize, int batchSize, byte distanceFunction) {
        if ((minBatchSize & 3) != 0) {
            throw new IllegalArgumentException("Batch size must be a multiple of 3");
        }

        var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();
        var toIndex = Math.min(currentIndex + batchSize, vectorReader.size());

        var vectors = new MemorySegment[minBatchSize];
        var clusterIndexes = new int[minBatchSize];
        var batches = (toIndex - currentIndex + minBatchSize - 1) / minBatchSize;
        var closestCentroids = new int[minBatchSize];

        var shuffledBatches = PermutationSampler.natural(batches);
        PermutationSampler.shuffle(rng, shuffledBatches);

        var clustersChangedLimit = (int) ((toIndex - currentIndex) * 0.001);
        int clustersChanged = clustersChangedLimit + 1;


        for (int iteration = 0; iteration < iterations && clustersChanged > clustersChangedLimit; iteration++) {
            clustersChanged = 0;
            for (var batchIndex : shuffledBatches) {
                var localIndexFrom = currentIndex + batchIndex * minBatchSize;
                var actualBatchSize = Math.min(localIndexFrom + minBatchSize, toIndex) - localIndexFrom;

                if ((actualBatchSize & 3) == 0) {
                    findClosestCentroidsFastPath(actualBatchSize, distanceFunction, vectors, clusterIndexes,
                            closestCentroids);
                } else {
                    for (int i = 0; i < actualBatchSize; i++) {
                        var vector = vectorReader.read(localIndexFrom + i);

                        vectors[i] = vector;
                        clusterIndexes[i] = Distance.findClosestVector(centroids, vector, subVecOffset, subVecSize, distanceFunction
                        );
                    }
                }

                for (int i = 0; i < actualBatchSize; i++) {
                    var clusterIndex = clusterIndexes[i];

                    var prevClusterIndex = clusterIndexesPerVector[i + localIndexFrom];
                    clusterIndexesPerVector[i + localIndexFrom] = clusterIndex;
                    centroidsSamplesCount[clusterIndex]++;

                    if (prevClusterIndex != clusterIndex) {
                        clustersChanged++;

                        if (prevClusterIndex >= 0) {
                            centroidsSamplesCount[prevClusterIndex]--;
                        }
                    }

                    var learningRate = 1.0f / centroidsSamplesCount[clusterIndex];

                    computeGradientStep(centroids, clusterIndex * subVecSize, vectors[i], subVecOffset, subVecSize,
                            learningRate);
                }
            }

            if (clustersChanged <= clustersChangedLimit) {
                System.out.printf("KMeans preliminary completed for %d, iteration %d, clusters changed %d," +
                                "  limit of changed clusters %d%n",
                        id, iteration, clustersChanged, clustersChangedLimit);
            }
        }


        currentIndex = toIndex;
        assert currentIndex <= vectorReader.size();
        return currentIndex == vectorReader.size();
    }

    private void findClosestCentroidsFastPath(int batchSize, byte distanceFunction,
                                              MemorySegment[] vectors,
                                              int[] clusterIndexes,
                                              int[] result) {
        for (int i = 0; i < batchSize; i += 4) {
            var index = currentIndex + i;

            var vector1 = vectorReader.read(index);
            var vector2 = vectorReader.read(index + 1);
            var vector3 = vectorReader.read(index + 2);
            var vector4 = vectorReader.read(index + 3);

            vectors[i] = vector1;
            vectors[i + 1] = vector2;
            vectors[i + 2] = vector3;
            vectors[i + 3] = vector4;

            Distance.findClosestVector(centroids, vector1, vector2, vector3, vector4, subVecOffset, subVecSize, result, distanceFunction
            );

            System.arraycopy(result, 0, clusterIndexes, i, 4);
        }
    }

    static void computeGradientStep(float[] centroids,
                                    int centroidOffset,
                                    MemorySegment vector,
                                    int vectorOffset,
                                    int vectorSize,
                                    float learningRate) {
        var index = 0;
        var learningRateVector = FloatVector.broadcast(SPECIES, learningRate);
        var loopBound = SPECIES.loopBound(vectorSize);
        var step = SPECIES.length();

        var vectorBytesOffset = (long) vectorOffset * Float.BYTES;
        var vectorStep = loopBound * Float.BYTES;
        for (; index < loopBound; index += step, vectorBytesOffset += vectorStep) {
            var centroidVector = FloatVector.fromArray(SPECIES, centroids, centroidOffset + index);
            var pointVector = FloatVector.fromMemorySegment(SPECIES, vector, vectorBytesOffset,
                    ByteOrder.nativeOrder());

            var diff = pointVector.sub(centroidVector);
            centroidVector = diff.fma(learningRateVector, centroidVector);
            centroidVector.intoArray(centroids, centroidOffset + index);
        }

        for (; index < vectorSize; index++) {
            centroids[centroidOffset + index] = centroids[centroidOffset + index] +
                    learningRate * (vector.getAtIndex(ValueLayout.JAVA_FLOAT, index + vectorOffset)
                            - centroids[centroidOffset + index]);
        }
    }
}