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
package jetbrains.exodus.entitystore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentEntityStoreCacheConfigTest {

    @Test
    fun `should return default max cache weight`() {
        // Given
        val config = PersistentEntityStoreConfig()

        // When
        val maxCacheWeight = config.entityIterableCacheWeight

        // Then
        val maxMemory = Runtime.getRuntime().maxMemory()
        assertTrue(maxCacheWeight in 1..<maxMemory)
    }

    @Test
    fun `should calculate max cache weight from params`() {
        // Given
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_MEMORY_PERCENTAGE, "50")
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_ENTITY_WEIGHT, "8")
        val config = PersistentEntityStoreConfig()

        // When
        val maxCacheWeight = config.entityIterableCacheWeight

        // Then
        val maxMemory = Runtime.getRuntime().maxMemory()
        assertEquals(maxCacheWeight, (maxMemory * 50) / (100 * 8))

        // Clean up
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_MEMORY_PERCENTAGE)
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_ENTITY_WEIGHT)
    }

    @Test
    fun `should set cache deferred size by default`() {
        // Given
        val config = PersistentEntityStoreConfig()

        // Then
        assertTrue(config.entityIterableCacheDeferredSize > 0)
    }

    @Test
    fun `should return cache size params if set explicitly`() {
        // Given
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_SIZE, "777")
        val config = PersistentEntityStoreConfig()

        // When
        assertEquals(777, config.entityIterableCacheSize)
        // Should be the same if not set explicitly
        assertEquals(777, config.entityIterableCacheDeferredSize)

        // Clean up
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_SIZE)
    }

    @Test
    fun `should set cache deferred explicitly`() {
        // Given
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_SIZE, "777")
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_DEFERRED_SIZE, "888")
        val config = PersistentEntityStoreConfig()

        // Then
        assertEquals(888, config.entityIterableCacheDeferredSize)

        // Clean up
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_SIZE)
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_DEFERRED_SIZE)
    }

    @Test
    fun `should set default cache count`() {
        // Given
        val config = PersistentEntityStoreConfig()

        // Then
        assertTrue(config.entityIterableCacheThreadCount > 0)
        assertTrue(config.entityIterableCacheCountsThreadCount > 0)
    }

    @Test
    fun `should set when only cache thread count set`() {
        // Given
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_THREAD_COUNT, "8")
        val config = PersistentEntityStoreConfig()

        // Then
        assertEquals(4, config.entityIterableCacheThreadCount)
        assertEquals(4, config.entityIterableCacheCountsThreadCount)

        // Clean up
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_THREAD_COUNT)
    }

    @Test
    fun `should set when cache counts thread count set`() {
        // Given
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_THREAD_COUNT, "5")
        System.setProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_COUNTS_THREAD_COUNT, "3")
        val config = PersistentEntityStoreConfig()

        // Then
        assertEquals(5, config.entityIterableCacheThreadCount)
        assertEquals(3, config.entityIterableCacheCountsThreadCount)

        // Clean up
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_THREAD_COUNT)
        System.clearProperty(PersistentEntityStoreConfig.ENTITY_ITERABLE_CACHE_COUNTS_THREAD_COUNT)
    }
}