package io.github.androidaql.storage

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Constant-size, multi-archive circular binary time-series store (`.aqlring`),
 * implementing the byte-level layout specified in `docs/binary_storage_format.md`.
 *
 * - File Header: 64 bytes (`AQLRING1`)
 * - Archive Descriptors: 3 * 32 = 96 bytes
 * - Archive 0 (High-Res): 15s step, 80,640 slots (14 days)
 * - Archive 1 (Medium-Res): 300s step, 25,920 slots (90 days)
 * - Archive 2 (Long-Term): 3600s step, 17,520 slots (2 years)
 * - Total Constant Size: 3,970,720 bytes (~3.79 MB)
 */
class AqlRingBufferStore(
    val file: File,
    val deviceId: Long = 1L,
    val archiveSpecs: List<ArchiveSpec> = DEFAULT_ARCHIVES
) : Closeable {

    data class ArchiveSpec(
        val stepSec: Int,
        val slotCount: Int
    )

    data class ArchiveDescriptor(
        val index: Int,
        val stepSec: Int,
        val slotCount: Int,
        val slotSizeBytes: Int,
        val dataOffsetBytes: Int,
        var lastBucketSec: Long
    )

    data class RawSampleInput(
        val timestampEpochSec: Long,
        val knownPlaceId: Int,
        val co2Ppm: Int,
        val pm25Ugm3: Int,
        val pm10Ugm3: Int,
        val tempC: Float,
        val humidityPct: Float,
        val batteryPct: Int = 255,
        val rssiDbm: Int = -128,
        val speedMps: Float? = null,
        val gpsAccuracyM: Float? = null
    )

    data class RingSlotRecord(
        val bucketEpochSec: Long,
        val sampleCount: Int,
        val knownPlaceId: Int,
        val co2PpmMean: Int,
        val co2PpmMin: Int,
        val co2PpmMax: Int,
        val pm25MeanX10: Int,
        val pm25Max: Int,
        val pm10MeanX10: Int,
        val pm10Max: Int,
        val tempCMeanX10: Int,
        val humidityMeanX10: Int,
        val batteryPctLast: Int,
        val rssiDbmLast: Int,
        val speedMpsX100: Int,
        val gpsAccuracyMX10: Int
    ) {
        val pm25Mean: Float get() = pm25MeanX10 / 10.0f
        val pm10Mean: Float get() = pm10MeanX10 / 10.0f
        val tempCMean: Float get() = tempCMeanX10 / 10.0f
        val humidityPctMean: Float get() = humidityMeanX10 / 10.0f
        val speedMps: Float? get() = if (speedMpsX100 == SENTINEL_UINT16) null else speedMpsX100 / 100.0f
        val gpsAccuracyM: Float? get() = if (gpsAccuracyMX10 == SENTINEL_UINT16) null else gpsAccuracyMX10 / 10.0f
    }

    data class SliceStatistics(
        val slotCount: Int,
        val totalSamples: Int,
        val durationSec: Long,
        val co2Min: Int,
        val co2Max: Int,
        val co2Mean: Float,
        val co2Median: Int,
        val co2P95: Int,
        val minutesCo2Above1000: Float,
        val pm25Mean: Float,
        val pm25Max: Int,
        val pm10Mean: Float,
        val pm10Max: Int,
        val totalDistanceMetersEstimate: Float
    )

    private val raf: RandomAccessFile
    private val channel get() = raf.channel
    private val descriptors = mutableListOf<ArchiveDescriptor>()

    init {
        file.parentFile?.mkdirs()
        val exists = file.exists() && file.length() >= HEADER_SIZE + archiveSpecs.size * ARCHIVE_DESC_SIZE
        raf = RandomAccessFile(file, "rw")
        if (!exists || !verifyAndLoadHeader()) {
            initializeNewFile()
        }
    }

    private fun verifyAndLoadHeader(): Boolean {
        val headerBuf = ByteBuffer.allocate(HEADER_SIZE + archiveSpecs.size * ARCHIVE_DESC_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        val read = channel.read(headerBuf)
        if (read < headerBuf.capacity()) return false
        headerBuf.flip()

        val magicBytes = ByteArray(8)
        headerBuf.get(magicBytes)
        if (String(magicBytes, Charsets.US_ASCII) != MAGIC_HEADER) return false

        val version = headerBuf.int
        val archiveCount = headerBuf.int
        if (version != FORMAT_VERSION || archiveCount != archiveSpecs.size) return false

        headerBuf.position(HEADER_SIZE)
        descriptors.clear()
        for (i in 0 until archiveCount) {
            val stepSec = headerBuf.int
            val slotCount = headerBuf.int
            val slotSizeBytes = headerBuf.int
            val dataOffsetBytes = headerBuf.int
            val lastBucketSec = headerBuf.long
            headerBuf.long // reserved
            if (stepSec != archiveSpecs[i].stepSec ||
                slotCount != archiveSpecs[i].slotCount ||
                slotSizeBytes != SLOT_SIZE_BYTES
            ) {
                return false
            }
            descriptors.add(
                ArchiveDescriptor(
                    index = i,
                    stepSec = stepSec,
                    slotCount = slotCount,
                    slotSizeBytes = slotSizeBytes,
                    dataOffsetBytes = dataOffsetBytes,
                    lastBucketSec = lastBucketSec
                )
            )
        }
        return true
    }

    private fun initializeNewFile() {
        val headerSectionSize = HEADER_SIZE + archiveSpecs.size * ARCHIVE_DESC_SIZE
        var nextDataOffset = headerSectionSize
        descriptors.clear()
        for ((idx, spec) in archiveSpecs.withIndex()) {
            descriptors.add(
                ArchiveDescriptor(
                    index = idx,
                    stepSec = spec.stepSec,
                    slotCount = spec.slotCount,
                    slotSizeBytes = SLOT_SIZE_BYTES,
                    dataOffsetBytes = nextDataOffset,
                    lastBucketSec = -1L
                )
            )
            nextDataOffset += spec.slotCount * SLOT_SIZE_BYTES
        }

        raf.setLength(nextDataOffset.toLong())
        val buf = ByteBuffer.allocate(headerSectionSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
        buf.putInt(FORMAT_VERSION)
        buf.putInt(archiveSpecs.size)
        buf.putLong(deviceId)
        buf.putLong(System.currentTimeMillis())
        buf.position(HEADER_SIZE)

        for (desc in descriptors) {
            buf.putInt(desc.stepSec)
            buf.putInt(desc.slotCount)
            buf.putInt(desc.slotSizeBytes)
            buf.putInt(desc.dataOffsetBytes)
            buf.putLong(desc.lastBucketSec)
            buf.putLong(0L)
        }
        buf.flip()
        channel.position(0)
        while (buf.hasRemaining()) {
            channel.write(buf)
        }
        // Zero out all archive data regions so newly allocated files never contain garbage
        clearDataRegion(headerSectionSize.toLong(), (nextDataOffset - headerSectionSize).toLong())
        channel.force(true)
    }

    private fun clearDataRegion(startOffset: Long, byteLength: Long) {
        val zeroChunk = ByteBuffer.allocate(min(65536L, byteLength).toInt())
        var remaining = byteLength
        var pos = startOffset
        while (remaining > 0) {
            zeroChunk.clear()
            val toWrite = min(zeroChunk.capacity().toLong(), remaining).toInt()
            zeroChunk.limit(toWrite)
            channel.write(zeroChunk, pos)
            pos += toWrite
            remaining -= toWrite
        }
    }

    @Synchronized
    fun writeSample(sample: RawSampleInput) {
        for (desc in descriptors) {
            writeSampleToArchive(desc, sample)
        }
    }

    private fun writeSampleToArchive(desc: ArchiveDescriptor, sample: RawSampleInput) {
        val step = desc.stepSec.toLong()
        val bucketSec = (sample.timestampEpochSec / step) * step

        // 1. Advance-and-clear skipped slots if time advanced forward
        if (desc.lastBucketSec >= 0L && bucketSec > desc.lastBucketSec) {
            val stepsElapsed = (bucketSec - desc.lastBucketSec) / step
            if (stepsElapsed >= desc.slotCount) {
                clearDataRegion(
                    desc.dataOffsetBytes.toLong(),
                    (desc.slotCount * SLOT_SIZE_BYTES).toLong()
                )
            } else {
                val zeroSlot = ByteBuffer.allocate(SLOT_SIZE_BYTES)
                val lastBucketIndex = desc.lastBucketSec / step
                for (k in 1..stepsElapsed) {
                    val skippedSlotIdx = ((lastBucketIndex + k) % desc.slotCount).toInt()
                    val offset = desc.dataOffsetBytes.toLong() + skippedSlotIdx.toLong() * SLOT_SIZE_BYTES
                    zeroSlot.clear()
                    channel.write(zeroSlot, offset)
                }
            }
        }

        val slotIdx = ((bucketSec / step) % desc.slotCount).toInt()
        val slotOffset = desc.dataOffsetBytes.toLong() + slotIdx.toLong() * SLOT_SIZE_BYTES
        val existing = readSlotAtOffset(slotOffset)

        val tempX10 = (sample.tempC * 10.0f).roundToInt().coerceIn(-32768, 32767)
        val humX10 = (sample.humidityPct * 10.0f).roundToInt().coerceIn(0, 1000)
        val pm25X10 = (sample.pm25Ugm3 * 10).coerceIn(0, 65535)
        val pm10X10 = (sample.pm10Ugm3 * 10).coerceIn(0, 65535)
        val speedX100 = sample.speedMps?.let { (it * 100.0f).roundToInt().coerceIn(0, 65534) } ?: SENTINEL_UINT16
        val accX10 = sample.gpsAccuracyM?.let { (it * 10.0f).roundToInt().coerceIn(0, 65534) } ?: SENTINEL_UINT16

        val updated = if (existing != null && existing.bucketEpochSec == bucketSec && existing.sampleCount > 0) {
            val oldN = existing.sampleCount
            val newN = (oldN + 1).coerceAtMost(65535)
            fun avg(oldVal: Int, newVal: Int): Int = ((oldVal.toLong() * oldN + newVal) / newN).toInt()

            val mergedSpeed = when {
                sample.knownPlaceId > 0 -> SENTINEL_UINT16
                speedX100 != SENTINEL_UINT16 && existing.speedMpsX100 != SENTINEL_UINT16 ->
                    avg(existing.speedMpsX100, speedX100)
                speedX100 != SENTINEL_UINT16 -> speedX100
                else -> existing.speedMpsX100
            }
            val mergedAcc = when {
                sample.knownPlaceId > 0 -> SENTINEL_UINT16
                accX10 != SENTINEL_UINT16 && existing.gpsAccuracyMX10 != SENTINEL_UINT16 ->
                    avg(existing.gpsAccuracyMX10, accX10)
                accX10 != SENTINEL_UINT16 -> accX10
                else -> existing.gpsAccuracyMX10
            }

            RingSlotRecord(
                bucketEpochSec = bucketSec,
                sampleCount = newN,
                knownPlaceId = sample.knownPlaceId,
                co2PpmMean = avg(existing.co2PpmMean, sample.co2Ppm),
                co2PpmMin = min(existing.co2PpmMin, sample.co2Ppm),
                co2PpmMax = max(existing.co2PpmMax, sample.co2Ppm),
                pm25MeanX10 = avg(existing.pm25MeanX10, pm25X10),
                pm25Max = max(existing.pm25Max, sample.pm25Ugm3),
                pm10MeanX10 = avg(existing.pm10MeanX10, pm10X10),
                pm10Max = max(existing.pm10Max, sample.pm10Ugm3),
                tempCMeanX10 = avg(existing.tempCMeanX10, tempX10),
                humidityMeanX10 = avg(existing.humidityMeanX10, humX10),
                batteryPctLast = sample.batteryPct.coerceIn(0, 255),
                rssiDbmLast = sample.rssiDbm.coerceIn(-128, 127),
                speedMpsX100 = mergedSpeed,
                gpsAccuracyMX10 = mergedAcc
            )
        } else {
            RingSlotRecord(
                bucketEpochSec = bucketSec,
                sampleCount = 1,
                knownPlaceId = sample.knownPlaceId,
                co2PpmMean = sample.co2Ppm.coerceIn(0, 65535),
                co2PpmMin = sample.co2Ppm.coerceIn(0, 65535),
                co2PpmMax = sample.co2Ppm.coerceIn(0, 65535),
                pm25MeanX10 = pm25X10,
                pm25Max = sample.pm25Ugm3.coerceIn(0, 65535),
                pm10MeanX10 = pm10X10,
                pm10Max = sample.pm10Ugm3.coerceIn(0, 65535),
                tempCMeanX10 = tempX10,
                humidityMeanX10 = humX10,
                batteryPctLast = sample.batteryPct.coerceIn(0, 255),
                rssiDbmLast = sample.rssiDbm.coerceIn(-128, 127),
                speedMpsX100 = speedX100,
                gpsAccuracyMX10 = accX10
            )
        }

        writeSlotAtOffset(slotOffset, updated)

        if (bucketSec > desc.lastBucketSec) {
            desc.lastBucketSec = bucketSec
            val descLastBucketOffset = (HEADER_SIZE + desc.index * ARCHIVE_DESC_SIZE + 16).toLong()
            val lbBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(bucketSec)
            lbBuf.flip()
            channel.write(lbBuf, descLastBucketOffset)
        }
    }

    @Synchronized
    fun queryRange(
        archiveIndex: Int,
        startEpochSec: Long,
        endEpochSec: Long
    ): List<RingSlotRecord> {
        val desc = descriptors[archiveIndex]
        val step = desc.stepSec.toLong()
        val alignedStart = (startEpochSec / step) * step
        val alignedEnd = (endEpochSec / step) * step
        if (alignedEnd < alignedStart) return emptyList()

        val totalRequestedSteps = ((alignedEnd - alignedStart) / step) + 1
        val results = mutableListOf<RingSlotRecord>()

        if (totalRequestedSteps <= desc.slotCount) {
            var bucket = alignedStart
            while (bucket <= alignedEnd) {
                val slotIdx = ((bucket / step) % desc.slotCount).toInt()
                val offset = desc.dataOffsetBytes.toLong() + slotIdx.toLong() * SLOT_SIZE_BYTES
                val slot = readSlotAtOffset(offset)
                if (slot != null && slot.sampleCount > 0 && slot.bucketEpochSec == bucket) {
                    results.add(slot)
                }
                bucket += step
            }
        } else {
            for (slotIdx in 0 until desc.slotCount) {
                val offset = desc.dataOffsetBytes.toLong() + slotIdx.toLong() * SLOT_SIZE_BYTES
                val slot = readSlotAtOffset(offset)
                if (slot != null && slot.sampleCount > 0 &&
                    slot.bucketEpochSec in alignedStart..alignedEnd
                ) {
                    results.add(slot)
                }
            }
            results.sortBy { it.bucketEpochSec }
        }
        return results
    }

    /**
     * Marks stationary slots within `[startEpochSec, endEpochSec]` as belonging to `knownPlaceId`
     * and clears their speed/GPS accuracy to `SENTINEL_UINT16` (`null`) when a user registers
     * their current Wi-Fi network as a Known Place during an indoor session.
     */
    @Synchronized
    fun markStationaryRangeAsKnownPlace(
        startEpochSec: Long,
        endEpochSec: Long,
        knownPlaceId: Int
    ) {
        if (knownPlaceId <= 0 || endEpochSec < startEpochSec) return
        for (desc in descriptors) {
            val step = desc.stepSec.toLong()
            val alignedStart = (startEpochSec / step) * step
            val alignedEnd = (endEpochSec / step) * step
            var bucket = alignedStart
            while (bucket <= alignedEnd) {
                val slotIdx = ((bucket / step) % desc.slotCount).toInt()
                val offset = desc.dataOffsetBytes.toLong() + slotIdx.toLong() * SLOT_SIZE_BYTES
                val slot = readSlotAtOffset(offset)
                if (slot != null && slot.sampleCount > 0 && slot.bucketEpochSec == bucket) {
                    val spd = slot.speedMps
                    if (spd == null || spd < 1.0f) {
                        writeSlotAtOffset(
                            offset,
                            slot.copy(
                                knownPlaceId = knownPlaceId,
                                speedMpsX100 = SENTINEL_UINT16,
                                gpsAccuracyMX10 = SENTINEL_UINT16
                            )
                        )
                    }
                }
                bucket += step
            }
        }
    }

    /**
     * Clears `knownPlaceId` back to `0` across all archives within `[startEpochSec, endEpochSec]`
     * for slots that occurred during a confirmed field excursion.
     */
    @Synchronized
    fun clearKnownPlaceInRange(
        startEpochSec: Long,
        endEpochSec: Long
    ) {
        if (endEpochSec < startEpochSec) return
        for (desc in descriptors) {
            val step = desc.stepSec.toLong()
            val alignedStart = (startEpochSec / step) * step
            val alignedEnd = (endEpochSec / step) * step
            var bucket = alignedStart
            while (bucket <= alignedEnd) {
                val slotIdx = ((bucket / step) % desc.slotCount).toInt()
                val offset = desc.dataOffsetBytes.toLong() + slotIdx.toLong() * SLOT_SIZE_BYTES
                val slot = readSlotAtOffset(offset)
                if (slot != null && slot.sampleCount > 0 && slot.bucketEpochSec == bucket && slot.knownPlaceId != 0) {
                    writeSlotAtOffset(offset, slot.copy(knownPlaceId = 0))
                }
                bucket += step
            }
        }
    }

    /**
     * Extracts raw Archive 0 slots within `[startEpochSec, endEpochSec]` into an immutable
     * `AQLP` binary blob for indefinite pinned retention (`PINNED_SLICE_DATA`).
     */
    @Synchronized
    fun extractPinnedBlob(startEpochSec: Long, endEpochSec: Long): ByteArray {
        val slots = queryRange(0, startEpochSec, endEpochSec)
        return encodePinnedBlob(slots)
    }

    fun getArchiveDescriptor(index: Int): ArchiveDescriptor = descriptors[index]

    private fun readSlotAtOffset(offset: Long): RingSlotRecord? {
        val buf = ByteBuffer.allocate(SLOT_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val n = channel.read(buf, offset)
        if (n < SLOT_SIZE_BYTES) return null
        buf.flip()
        return decodeSlotFromBuffer(buf)
    }

    private fun writeSlotAtOffset(offset: Long, record: RingSlotRecord) {
        val buf = ByteBuffer.allocate(SLOT_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        encodeSlotToBuffer(buf, record)
        buf.flip()
        channel.write(buf, offset)
    }

    override fun close() {
        raf.close()
    }

    companion object {
        const val MAGIC_HEADER = "AQLRING1"
        const val MAGIC_PINNED = "AQLP"
        const val FORMAT_VERSION = 1
        const val HEADER_SIZE = 64
        const val ARCHIVE_DESC_SIZE = 32
        const val SLOT_SIZE_BYTES = 32
        const val SENTINEL_UINT16 = 0xFFFF

        val DEFAULT_ARCHIVES = listOf(
            ArchiveSpec(stepSec = 15, slotCount = 80_640),   // 14 days @ 15s
            ArchiveSpec(stepSec = 300, slotCount = 25_920),  // 90 days @ 5m
            ArchiveSpec(stepSec = 3600, slotCount = 17_520)  // 2 years @ 1h
        )

        fun encodeSlotToBuffer(buf: ByteBuffer, record: RingSlotRecord) {
            buf.putInt(record.bucketEpochSec.toInt())
            buf.putShort(record.sampleCount.toShort())
            buf.putShort(record.knownPlaceId.toShort())
            buf.putShort(record.co2PpmMean.toShort())
            buf.putShort(record.co2PpmMin.toShort())
            buf.putShort(record.co2PpmMax.toShort())
            buf.putShort(record.pm25MeanX10.toShort())
            buf.putShort(record.pm25Max.toShort())
            buf.putShort(record.pm10MeanX10.toShort())
            buf.putShort(record.pm10Max.toShort())
            buf.putShort(record.tempCMeanX10.toShort())
            buf.putShort(record.humidityMeanX10.toShort())
            buf.put(record.batteryPctLast.toByte())
            buf.put(record.rssiDbmLast.toByte())
            buf.putShort(record.speedMpsX100.toShort())
            buf.putShort(record.gpsAccuracyMX10.toShort())
        }

        fun decodeSlotFromBuffer(buf: ByteBuffer): RingSlotRecord {
            val bucketEpochSec = buf.int.toLong() and 0xFFFF_FFFFL
            val sampleCount = buf.short.toInt() and 0xFFFF
            val knownPlaceId = buf.short.toInt() and 0xFFFF
            val co2Mean = buf.short.toInt() and 0xFFFF
            val co2Min = buf.short.toInt() and 0xFFFF
            val co2Max = buf.short.toInt() and 0xFFFF
            val pm25MeanX10 = buf.short.toInt() and 0xFFFF
            val pm25Max = buf.short.toInt() and 0xFFFF
            val pm10MeanX10 = buf.short.toInt() and 0xFFFF
            val pm10Max = buf.short.toInt() and 0xFFFF
            val tempCMeanX10 = buf.short.toInt() // signed int16
            val humidityMeanX10 = buf.short.toInt() and 0xFFFF
            val batteryPct = buf.get().toInt() and 0xFF
            val rssiDbm = buf.get().toInt() // signed int8
            val speedMpsX100 = buf.short.toInt() and 0xFFFF
            val gpsAccuracyMX10 = buf.short.toInt() and 0xFFFF

            return RingSlotRecord(
                bucketEpochSec = bucketEpochSec,
                sampleCount = sampleCount,
                knownPlaceId = knownPlaceId,
                co2PpmMean = co2Mean,
                co2PpmMin = co2Min,
                co2PpmMax = co2Max,
                pm25MeanX10 = pm25MeanX10,
                pm25Max = pm25Max,
                pm10MeanX10 = pm10MeanX10,
                pm10Max = pm10Max,
                tempCMeanX10 = tempCMeanX10,
                humidityMeanX10 = humidityMeanX10,
                batteryPctLast = batteryPct,
                rssiDbmLast = rssiDbm,
                speedMpsX100 = speedMpsX100,
                gpsAccuracyMX10 = gpsAccuracyMX10
            )
        }

        fun encodePinnedBlob(slots: List<RingSlotRecord>): ByteArray {
            val buf = ByteBuffer.allocate(8 + slots.size * SLOT_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(MAGIC_PINNED.toByteArray(Charsets.US_ASCII))
            buf.putInt(slots.size)
            for (slot in slots) {
                encodeSlotToBuffer(buf, slot)
            }
            return buf.array()
        }

        fun decodePinnedBlob(blob: ByteArray): List<RingSlotRecord> {
            if (blob.size < 8) return emptyList()
            val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            buf.get(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC_PINNED) return emptyList()
            val count = buf.int
            val out = ArrayList<RingSlotRecord>(count)
            for (i in 0 until count) {
                if (buf.remaining() < SLOT_SIZE_BYTES) break
                out.add(decodeSlotFromBuffer(buf))
            }
            return out
        }

        fun computeSliceStats(slots: List<RingSlotRecord>, stepSec: Int = 15): SliceStatistics {
            if (slots.isEmpty()) {
                return SliceStatistics(0, 0, 0L, 0, 0, 0f, 0, 0, 0f, 0f, 0, 0f, 0, 0f)
            }
            var co2Min = Int.MAX_VALUE
            var co2Max = 0
            var co2Sum = 0L
            var pm25SumX10 = 0L
            var pm25Max = 0
            var pm10SumX10 = 0L
            var pm10Max = 0
            var totalSamples = 0
            var slotsAbove1000 = 0
            var distanceMeters = 0f

            val co2Means = IntArray(slots.size)
            for ((i, s) in slots.withIndex()) {
                co2Min = min(co2Min, s.co2PpmMin)
                co2Max = max(co2Max, s.co2PpmMax)
                co2Sum += s.co2PpmMean
                co2Means[i] = s.co2PpmMean
                if (s.co2PpmMean >= 1000) slotsAbove1000++

                pm25SumX10 += s.pm25MeanX10
                pm25Max = max(pm25Max, s.pm25Max)
                pm10SumX10 += s.pm10MeanX10
                pm10Max = max(pm10Max, s.pm10Max)
                totalSamples += s.sampleCount

                val spd = s.speedMps
                val acc = s.gpsAccuracyM
                // Only accumulate distance when GPS accuracy is reasonable (<= 50m)
                if (spd != null && (acc == null || acc <= 50.0f)) {
                    distanceMeters += spd * stepSec
                }
            }
            co2Means.sort()
            val median = co2Means[co2Means.size / 2]
            val p95Idx = ((co2Means.size - 1) * 0.95).toInt().coerceIn(0, co2Means.size - 1)
            val p95 = co2Means[p95Idx]
            val durationSec = max(stepSec.toLong(), slots.last().bucketEpochSec - slots.first().bucketEpochSec + stepSec)

            return SliceStatistics(
                slotCount = slots.size,
                totalSamples = totalSamples,
                durationSec = durationSec,
                co2Min = if (co2Min == Int.MAX_VALUE) 0 else co2Min,
                co2Max = co2Max,
                co2Mean = co2Sum.toFloat() / slots.size,
                co2Median = median,
                co2P95 = p95,
                minutesCo2Above1000 = (slotsAbove1000 * stepSec) / 60.0f,
                pm25Mean = (pm25SumX10.toFloat() / slots.size) / 10.0f,
                pm25Max = pm25Max,
                pm10Mean = (pm10SumX10.toFloat() / slots.size) / 10.0f,
                pm10Max = pm10Max,
                totalDistanceMetersEstimate = distanceMeters
            )
        }
    }
}
