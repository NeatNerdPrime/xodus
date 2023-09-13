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

import jetbrains.exodus.diskann.siftbench.SiftBenchUtils;
import org.apache.commons.rng.RestorableUniformRandomProvider;
import org.apache.commons.rng.simple.RandomSource;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

public class DiskANNTest {
    @Test
    public void testFindLoadedVectorsL2Distance() throws Exception {
        var buildDir = System.getProperty("exodus.tests.buildDirectory");
        if (buildDir == null) {
            Assert.fail("exodus.tests.buildDirectory is not set !!!");
        }

        var vectorDimensions = 64;

        var vectorsCount = 10_000;

        var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();
        var vectors = new float[vectorsCount][];
        for (var i = 0; i < vectorsCount; i++) {
            var vector = new float[vectorDimensions];
            vectors[i] = vector;
        }

        generateUniqueVectorSet(vectors, rng);

        var dbDir = Files.createTempDirectory(Path.of(buildDir), "testFindLoadedVectorsL2Distance");
        dbDir.toFile().deleteOnExit();

        var ts1 = System.nanoTime();
        Path dataLocation;
        try (var dataBuilder = DataStore.create(128, "test_index", dbDir)) {
            for (var vector : vectors) {
                dataBuilder.add(vector);
            }

            dataLocation = dataBuilder.dataLocation();
        }

        IndexBuilder.buildIndex("test_index", vectorDimensions, dbDir, dataLocation,
                4 * 1024 * 1024, L2PQQuantizer.INSTANCE, L2DistanceFunction.INSTANCE);
        var ts2 = System.nanoTime();
        System.out.printf("Index built in %d ms.%n", (ts2 - ts1) / 1000000);

        try (var indexReader = new IndexReader("test_index", vectorDimensions, dbDir, 64 * 1024 * 1024,
                L2PQQuantizer.INSTANCE, L2DistanceFunction.INSTANCE)) {
            var errorsCount = 0;
            ts1 = System.nanoTime();
            for (var j = 0; j < vectorsCount; j++) {
                var vector = vectors[j];
                var result = new long[1];
                indexReader.nearest(vector, result, 1);
                Assert.assertEquals("j = " + j, 1, result.length);

                if (j != result[0]) {
                    errorsCount++;
                }

                if ((j + 1) % 1_000 == 0) {
                    System.out.println("Processed " + (j + 1));
                }
            }

            ts2 = System.nanoTime();
            var errorPercentage = errorsCount * 100.0 / vectorsCount;

            System.out.printf("Avg. query %d time us, errors: %f%%, pq error %f%%, cache hits %d%% %n",
                    (ts2 - ts1) / 1000 / vectorsCount, errorPercentage, indexReader.pqErrorAvg(),
                    indexReader.hits());
            Assert.assertTrue("Error percentage is too high " + errorPercentage + " > 1",
                    errorPercentage <= 1);
            Assert.assertTrue("PQ error is too high " + indexReader.pqErrorAvg() + " > 15",
                    indexReader.pqErrorAvg() <= 12);
            indexReader.deleteIndex();
        }
    }

    @Test
    public void testFindLoadedVectorsDotDistance() throws Exception {
        var buildDir = System.getProperty("exodus.tests.buildDirectory");
        if (buildDir == null) {
            Assert.fail("exodus.tests.buildDirectory is not set !!!");
        }

        var vectorDimensions = 64;

        var vectorsCount = 10_000;

        var rng = RandomSource.XO_RO_SHI_RO_128_PP.create();
        var vectors = new float[vectorsCount][];
        for (var i = 0; i < vectorsCount; i++) {
            var vector = new float[vectorDimensions];
            vectors[i] = vector;
        }

        generateUniqueVectorSet(vectors, rng);

        var queryVectors = new float[vectorsCount][vectorDimensions];
        generateUniqueVectorSet(queryVectors, rng);

        var groundTruth = calculateGroundTruthVectors(vectors, queryVectors, DotDistanceFunction.INSTANCE);
        var dbDir = Files.createTempDirectory(Path.of(buildDir), "testFindLoadedVectorsDotDistance");
        dbDir.toFile().deleteOnExit();
        var ts1 = System.nanoTime();

        Path dataLocation;
        try (var dataBuilder = DataStore.create(128, "test_index", dbDir)) {
            for (var vector : vectors) {
                dataBuilder.add(vector);
            }

            dataLocation = dataBuilder.dataLocation();
        }

        IndexBuilder.buildIndex("test_index", vectorDimensions, dbDir, dataLocation,
                4 * 1024 * 1024, L2PQQuantizer.INSTANCE, DotDistanceFunction.INSTANCE);

        var ts2 = System.nanoTime();
        System.out.printf("Index built in %d ms.%n", (ts2 - ts1) / 1000000);

        try (var indexReader = new IndexReader("test_index", vectorDimensions, dbDir, 64 * 1024 * 1024,
                L2PQQuantizer.INSTANCE, DotDistanceFunction.INSTANCE)) {
            var errorsCount = 0;

            ts1 = System.nanoTime();
            for (var j = 0; j < vectorsCount; j++) {
                var vector = queryVectors[j];
                var result = new long[1];
                indexReader.nearest(vector, result, 1);
                Assert.assertEquals("j = " + j, 1, result.length);

                if (groundTruth[j] != result[0]) {
                    errorsCount++;
                }

                if ((j + 1) % 1_000 == 0) {
                    System.out.println("Processed " + (j + 1));
                }
            }

            ts2 = System.nanoTime();
            var errorPercentage = errorsCount * 100.0 / vectorsCount;

            System.out.printf("Avg. query %d time us, errors: %f%%, pq error %f%%, cache hits %d%% %n",
                    (ts2 - ts1) / 1000 / vectorsCount, errorPercentage, indexReader.pqErrorAvg(),
                    indexReader.hits());
            Assert.assertTrue("Error percentage is too high " + errorPercentage + " > 1",
                    errorPercentage <= 5);
            Assert.assertTrue("PQ error is too high " + indexReader.pqErrorAvg() + " > 15",
                    indexReader.pqErrorAvg() <= 12);
            indexReader.deleteIndex();
        }
    }

    private static void generateUniqueVectorSet(float[][] vectors, RestorableUniformRandomProvider rng) {
        var addedVectors = new HashSet<FloatArrayHolder>();

        for (float[] vector : vectors) {
            var counter = 0;
            do {
                if (counter > 0) {
                    System.out.println("duplicate vector found " + counter + ", retrying...");
                }

                for (var j = 0; j < vector.length; j++) {
                    vector[j] = 10 * rng.nextFloat();
                }
                counter++;
            } while (!addedVectors.add(new FloatArrayHolder(vector)));
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static int[] calculateGroundTruthVectors(float[][] vectors, float[][] queryVectors,
                                                     final DistanceFunction distanceFunction) {
        var groundTruth = new int[vectors.length];
        for (int i = 0; i < queryVectors.length; i++) {
            var queryVector = queryVectors[i];

            var minDistance = Float.MAX_VALUE;
            var minDistanceVectorIndex = -1;

            for (int j = 0; j < vectors.length; j++) {
                var vector = vectors[j];
                var distance = distanceFunction.computeDistance(vector, 0, queryVector,
                        0, vector.length);

                if (distance < minDistance) {
                    minDistance = distance;
                    minDistanceVectorIndex = j;
                }
            }

            groundTruth[i] = minDistanceVectorIndex;
        }

        return groundTruth;
    }

    @Test
    public void testSearchSift10KVectors() throws IOException {
        runSiftBenchmarks(
                "siftsmall", "siftsmall.tar.gz",
                "siftsmall_base.fvecs", "siftsmall_query.fvecs",
                "siftsmall_groundtruth.ivecs", 128
        );

    }

    @SuppressWarnings("SameParameterValue")
    private void runSiftBenchmarks(
            String siftDir, String siftArchive, String siftBaseName,
            String queryFileName, String groundTruthFileName, int vectorDimensions
    ) throws IOException {
        var buildDir = System.getProperty("exodus.tests.buildDirectory");
        if (buildDir == null) {
            Assert.fail("exodus.tests.buildDirectory is not set !!!");
        }

        SiftBenchUtils.downloadSiftBenchmark(siftArchive, buildDir);

        var siftSmallDir = SiftBenchUtils.extractSiftDataSet(siftArchive, buildDir);

        var sifSmallFilesDir = siftSmallDir.toPath().resolve(siftDir);
        var siftSmallBase = sifSmallFilesDir.resolve(siftBaseName);

        System.out.println("Reading data vectors...");
        var vectors = SiftBenchUtils.readFVectors(siftSmallBase, vectorDimensions);

        System.out.printf("%d data vectors loaded with dimension %d%n",
                vectors.length, vectorDimensions);

        System.out.println("Reading queries...");
        var queryFile = sifSmallFilesDir.resolve(queryFileName);
        var queryVectors = SiftBenchUtils.readFVectors(queryFile, vectorDimensions);

        System.out.printf("%d queries are read%n", queryVectors.length);
        System.out.println("Reading ground truth...");

        var groundTruthFile = sifSmallFilesDir.resolve(groundTruthFileName);
        var groundTruth = SiftBenchUtils.readIVectors(groundTruthFile, 100);
        Assert.assertEquals(queryVectors.length, groundTruth.length);

        System.out.println("Ground truth is read");


        System.out.println("Building index...");

        var dbDir = Files.createTempDirectory(Path.of(buildDir), "testSearchSift10KVectors");
        dbDir.toFile().deleteOnExit();

        var ts1 = System.nanoTime();
        Path dataLocation;
        try (var dataBuilder = DataStore.create(128, "test_index", dbDir)) {
            for (var vector : vectors) {
                dataBuilder.add(vector);
            }

            dataLocation = dataBuilder.dataLocation();
        }
        IndexBuilder.buildIndex("test_index", vectorDimensions, dbDir, dataLocation,
                4 * 1024 * 1024, L2PQQuantizer.INSTANCE, L2DistanceFunction.INSTANCE);
        var ts2 = System.nanoTime();
        System.out.printf("Index built in %d ms.%n", (ts2 - ts1) / 1000000);

        try (var indexReader = new IndexReader("test_index", vectorDimensions, dbDir, 64 * 1024 * 1024,
                L2PQQuantizer.INSTANCE, L2DistanceFunction.INSTANCE)) {
            System.out.println("Searching...");

            var errorsCount = 0;
            ts1 = System.nanoTime();
            for (var index = 0; index < queryVectors.length; index++) {
                var vector = queryVectors[index];
                var result = new long[1];
                indexReader.nearest(vector, result, 1);

                Assert.assertEquals("j = " + index, 1, result.length);
                if (groundTruth[index][0] != result[0]) {
                    errorsCount++;
                }
            }
            ts2 = System.nanoTime();
            var errorPercentage = errorsCount * 100.0 / queryVectors.length;

            System.out.printf("Avg. query time : %d us, errors: %f%%  pq error %f%%, cache hits %d%%%n",
                    (ts2 - ts1) / 1000 / queryVectors.length, errorPercentage, indexReader.pqErrorAvg(),
                    indexReader.hits());
            Assert.assertTrue("PQ error is too high " + indexReader.pqErrorAvg() + " > 7.7",
                    indexReader.pqErrorAvg() <= 7.7);
            Assert.assertTrue("Error percentage is too high " + errorPercentage + " > 1.1",
                    errorPercentage <= 1.1);
            indexReader.deleteIndex();
        }

    }
}

record FloatArrayHolder(float[] floatArray) {
    @Override
    public int hashCode() {
        return Arrays.hashCode(floatArray);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof FloatArrayHolder) {
            return Arrays.equals(floatArray, ((FloatArrayHolder) obj).floatArray);
        }
        return false;
    }
}