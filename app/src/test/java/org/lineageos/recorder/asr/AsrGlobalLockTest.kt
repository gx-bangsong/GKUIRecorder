/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.recorder.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AsrGlobalLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun lockFile(): File = File(tmp.root, "asr/asr.lock")

    @Test
    fun secondAttemptIsRefusedWhileFirstIsHeld() {
        val first = AsrGlobalLock.tryAcquire(lockFile())
        assertNotNull(first)
        assertNull("same-process overlap must not succeed", AsrGlobalLock.tryAcquire(lockFile()))
        assertTrue(AsrGlobalLock.isHeld(lockFile()))
        first!!.close()
        assertFalse(AsrGlobalLock.isHeld(lockFile()))
    }

    @Test
    fun waiterGetsTheLockWhenHolderReleases() {
        val first = AsrGlobalLock.tryAcquire(lockFile())!!
        val pool = Executors.newSingleThreadExecutor()
        val waiter = pool.submit<Boolean> {
            AsrGlobalLock.acquire(lockFile()) { false }?.close() != null
        }
        Thread.sleep(700)
        first.close()
        assertTrue(waiter.get(10, TimeUnit.SECONDS))
        pool.shutdown()
    }

    @Test
    fun waitingStopsWhenCancelled() {
        val first = AsrGlobalLock.tryAcquire(lockFile())!!
        assertNull(AsrGlobalLock.acquire(lockFile()) { true })
        first.close()
    }

    @Test
    fun concurrentStartsNeverOverlap() {
        // Many "workers" race for the lock; at most one may be inside the critical section.
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val done = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.execute {
                AsrGlobalLock.acquire(lockFile()) { false }!!.use {
                    val now = inside.incrementAndGet()
                    maxInside.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                    Thread.sleep(50)
                    inside.decrementAndGet()
                }
                done.countDown()
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue("max concurrent holders was ${maxInside.get()}", maxInside.get() == 1)
    }
}
