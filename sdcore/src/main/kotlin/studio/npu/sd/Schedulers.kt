package studio.npu.sd

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The "noise schedule" maths. Each scheduler decides which timesteps the UNet sees and how
 * the UNet's prediction is turned into the next (less noisy) latent.
 */
interface Scheduler {
    /** Timestep value passed to the UNet at each step. */
    val timesteps: FloatArray

    /** Multiply the starting random noise by this. */
    val initNoiseSigma: Float

    /** Some schedulers scale the UNet input. Returns [sample] itself when no scaling is needed. */
    fun scaleModelInput(sample: FloatArray, stepIndex: Int): FloatArray = sample

    /** Turn the UNet output into the latent for the next step. [noise] is a fresh N(0,1) array (may be unused). */
    fun step(modelOutput: FloatArray, stepIndex: Int, sample: FloatArray, noise: () -> FloatArray): FloatArray

    /** Best guess of the final image latent after [stepIndex] (used for live previews). */
    fun previewLatent(): FloatArray?
}

private fun scaledLinearAlphasCumprod(
    betaStart: Double = 0.00085,
    betaEnd: Double = 0.012,
    trainSteps: Int = 1000,
): DoubleArray {
    val a = sqrt(betaStart)
    val b = sqrt(betaEnd)
    val out = DoubleArray(trainSteps)
    var prod = 1.0
    for (i in 0 until trainSteps) {
        val s = a + (b - a) * i / (trainSteps - 1)
        prod *= 1.0 - s * s
        out[i] = prod
    }
    return out
}

/** Latent Consistency Model scheduler (diffusers LCMScheduler). 2-8 steps, no negative prompt needed. */
class LcmScheduler(numSteps: Int, originalSteps: Int = 50) : Scheduler {
    private val alphasCumprod = scaledLinearAlphasCumprod()
    private val ts: IntArray
    private var lastDenoised: FloatArray? = null

    init {
        val k = 1000 / originalSteps
        val origin = IntArray(originalSteps) { (it + 1) * k - 1 }.reversedArray() // 999, 979, ... 19
        val n = numSteps.coerceIn(1, originalSteps)
        ts = IntArray(n) { i -> origin[floor(i * originalSteps.toDouble() / n).toInt()] }
    }

    override val timesteps = FloatArray(ts.size) { ts[it].toFloat() }
    override val initNoiseSigma = 1f

    override fun step(modelOutput: FloatArray, stepIndex: Int, sample: FloatArray, noise: () -> FloatArray): FloatArray {
        val t = ts[stepIndex]
        val prevT = if (stepIndex + 1 < ts.size) ts[stepIndex + 1] else -1
        val alphaT = alphasCumprod[t]
        val alphaPrev = if (prevT >= 0) alphasCumprod[prevT] else 1.0
        val betaT = 1.0 - alphaT
        val betaPrev = 1.0 - alphaPrev

        // Boundary condition scalings (sigma_data = 0.5, timestep_scaling = 10).
        val scaled = t * 10.0
        val cSkip = 0.25 / (scaled * scaled + 0.25)
        val cOut = scaled / sqrt(scaled * scaled + 0.25)

        val sqrtAlpha = sqrt(alphaT)
        val sqrtBeta = sqrt(betaT)
        val denoised = FloatArray(sample.size)
        for (i in sample.indices) {
            val x0 = (sample[i] - sqrtBeta * modelOutput[i]) / sqrtAlpha
            denoised[i] = (cOut * x0 + cSkip * sample[i]).toFloat()
        }
        lastDenoised = denoised
        if (stepIndex == ts.size - 1) return denoised

        val z = noise()
        val a = sqrt(alphaPrev)
        val b = sqrt(betaPrev)
        return FloatArray(sample.size) { i -> (a * denoised[i] + b * z[i]).toFloat() }
    }

    override fun previewLatent(): FloatArray? = lastDenoised
}

/** Euler scheduler (diffusers EulerDiscreteScheduler, "linspace" spacing) for normal SD 1.5 models. */
class EulerScheduler(numSteps: Int) : Scheduler {
    private val sigmas: DoubleArray
    private var lastX0: FloatArray? = null

    override val timesteps: FloatArray

    init {
        val ac = scaledLinearAlphasCumprod()
        val allSigmas = DoubleArray(1000) { sqrt((1 - ac[it]) / ac[it]) }
        val n = numSteps.coerceAtLeast(1)
        timesteps = FloatArray(n) { i -> (999.0 - i * 999.0 / maxOf(1, n - 1)).toFloat() }
        if (n == 1) timesteps[0] = 999f
        sigmas = DoubleArray(n + 1)
        for (i in 0 until n) {
            val t = timesteps[i].toDouble()
            val lo = floor(t).toInt().coerceIn(0, 999)
            val hi = (lo + 1).coerceAtMost(999)
            val w = t - lo
            sigmas[i] = allSigmas[lo] * (1 - w) + allSigmas[hi] * w
        }
        sigmas[n] = 0.0
    }

    override val initNoiseSigma = sigmas[0].toFloat()

    override fun scaleModelInput(sample: FloatArray, stepIndex: Int): FloatArray {
        val s = sigmas[stepIndex]
        val f = (1.0 / sqrt(s * s + 1.0)).toFloat()
        return FloatArray(sample.size) { sample[it] * f }
    }

    override fun step(modelOutput: FloatArray, stepIndex: Int, sample: FloatArray, noise: () -> FloatArray): FloatArray {
        val sigma = sigmas[stepIndex]
        val dt = (sigmas[stepIndex + 1] - sigma).toFloat()
        val x0 = FloatArray(sample.size) { (sample[it] - sigma * modelOutput[it]).toFloat() }
        lastX0 = x0
        // derivative = (sample - x0) / sigma = modelOutput (epsilon prediction)
        return FloatArray(sample.size) { sample[it] + modelOutput[it] * dt }
    }

    override fun previewLatent(): FloatArray? = lastX0
}

/**
 * Rectified-flow scheduler used by InstaFlow (1 step). The UNet predicts a "velocity";
 * we move the latent along it: x <- x + dt * v, with t going 1000 -> 0.
 */
class RectifiedFlowScheduler(numSteps: Int) : Scheduler {
    private val n = numSteps.coerceAtLeast(1)
    private var last: FloatArray? = null
    override val timesteps = FloatArray(n) { i -> ((1.0 - i.toDouble() / n) * 1000.0).toFloat() }
    override val initNoiseSigma = 1f

    override fun step(modelOutput: FloatArray, stepIndex: Int, sample: FloatArray, noise: () -> FloatArray): FloatArray {
        val dt = 1f / n
        val out = FloatArray(sample.size) { sample[it] + dt * modelOutput[it] }
        // Straight-line extrapolation to t = 0 gives a good preview.
        val remaining = (n - stepIndex - 1).toFloat() / n
        last = FloatArray(sample.size) { out[it] + remaining * modelOutput[it] }
        return out
    }

    override fun previewLatent(): FloatArray? = last
}

/** LCM "w" guidance embedding (diffusers get_guidance_scale_embedding). */
fun guidanceEmbedding(guidanceScale: Float, dim: Int = 256): FloatArray {
    val w = (guidanceScale - 1f) * 1000.0
    val half = dim / 2
    val k = ln(10000.0) / (half - 1)
    val out = FloatArray(dim)
    for (i in 0 until half) {
        val v = w * exp(-k * i)
        out[i] = kotlin.math.sin(v).toFloat()
        out[i + half] = kotlin.math.cos(v).toFloat()
    }
    return out
}
