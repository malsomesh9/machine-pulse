package com.machinepulse.edge.capture

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

internal data class SignalFeatures(
    val audioRms: Double,
    val audioPeak: Int,
    val audioClippingFraction: Double,
    val audioZeroCrossingRate: Double,
    val motionDynamicRms: Double,
    val motionMaxAxisRange: Double,
)

internal data class CaptureQuality(
    val accepted: Boolean,
    val reason: String,
)

data class BaselineComparison(
    val outOfBaseline: Boolean,
    val score: Int,
    val primaryExplanation: String,
    val audioRmsDeltaPercent: Int,
    val vibrationDeltaPercent: Int,
    val spectralProxyDeltaPercent: Int,
)

internal fun assessCaptureQuality(features: SignalFeatures): CaptureQuality {
    return when {
        features.motionMaxAxisRange > 1.5 -> CaptureQuality(
            accepted = false,
            reason = "Phone movement detected; keep it flat during capture.",
        )
        features.audioRms < 20.0 -> CaptureQuality(
            accepted = false,
            reason = "Audio level was too low; move the phone closer to the machine.",
        )
        features.audioClippingFraction > 0.01 -> CaptureQuality(
            accepted = false,
            reason = "Audio clipped; move the phone farther from the machine.",
        )
        else -> CaptureQuality(
            accepted = true,
            reason = "Placement and signal quality accepted.",
        )
    }
}

internal fun compareWithBaselines(
    observation: SignalFeatures,
    baselines: List<SignalFeatures>,
): BaselineComparison {
    require(baselines.isNotEmpty()) { "At least one accepted baseline is required." }

    val audio = deviation(
        value = observation.audioRms,
        baselineValues = baselines.map { it.audioRms },
        minimumRelativeTolerance = 0.30,
    )
    val vibration = deviation(
        value = observation.motionDynamicRms,
        baselineValues = baselines.map { it.motionDynamicRms },
        minimumRelativeTolerance = 0.60,
        absoluteFloor = 0.01,
    )
    val spectralProxy = deviation(
        value = observation.audioZeroCrossingRate,
        baselineValues = baselines.map { it.audioZeroCrossingRate },
        minimumRelativeTolerance = 0.25,
        absoluteFloor = 0.001,
    )

    val strongest = listOf(
        DeviationReason("Acoustic RMS", audio),
        DeviationReason("Vibration energy", vibration),
        DeviationReason("Acoustic spectral proxy", spectralProxy),
    ).maxBy { it.deviation.normalized }
    val score = (strongest.deviation.normalized * 100.0).toInt().coerceIn(0, 999)
    val outOfBaseline = score >= 100
    val direction = if (strongest.deviation.relativePercent >= 0) "increased" else "decreased"
    val explanation = if (outOfBaseline) {
        "${strongest.label} $direction ${abs(strongest.deviation.relativePercent).toInt()}%"
    } else {
        "Measured signals remain inside the learned baseline tolerance"
    }

    return BaselineComparison(
        outOfBaseline = outOfBaseline,
        score = score,
        primaryExplanation = explanation,
        audioRmsDeltaPercent = audio.relativePercent.toInt(),
        vibrationDeltaPercent = vibration.relativePercent.toInt(),
        spectralProxyDeltaPercent = spectralProxy.relativePercent.toInt(),
    )
}

private data class Deviation(
    val normalized: Double,
    val relativePercent: Double,
)

private data class DeviationReason(
    val label: String,
    val deviation: Deviation,
)

private fun deviation(
    value: Double,
    baselineValues: List<Double>,
    minimumRelativeTolerance: Double,
    absoluteFloor: Double = 1.0,
): Deviation {
    val mean = baselineValues.average()
    val variance = baselineValues
        .map { (it - mean).pow(2) }
        .average()
    val standardDeviation = sqrt(variance)
    val tolerance = max(
        max(abs(mean) * minimumRelativeTolerance, standardDeviation * 3.0),
        absoluteFloor,
    )
    val difference = value - mean
    val relativePercent = if (abs(mean) > 1e-9) difference / abs(mean) * 100.0 else 0.0
    return Deviation(
        normalized = abs(difference) / tolerance,
        relativePercent = relativePercent,
    )
}
