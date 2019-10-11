package apps

import convolution._
import lift.core.DSL._
import util.gen
import idealised.OpenCL._

class Convolution extends test_util.TestsWithExecutor {
  private val inputSize_small = 4096

  ignore("convolution versions produce same results") {
    val N = inputSize_small // TODO: this is still big for a test
    val random = new scala.util.Random()
    val matrix = Array.fill(N, N)(random.nextFloat * 10.0f)
    val weights = Array.fill(17)(random.nextFloat)

    val (lsX, gsX) = blurXTiled2D_sizes(N)
    val (lsY, gsY) = blurYTiled2DTiledLoadingTransposed_sizes(N)

    test_util.runsWithSameResult(Seq(
      ("originalX (CG017)", runOriginalKernel("CGO17_ConvolutionColumn_small.cl",
        N, lsX, gsX, matrix, weights)),
      ("dpiaX", runKernel(gen.OpenCLKernel(blurXTiled2D(N)),
        lsX, gsX, matrix, weights))
    ))
    test_util.runsWithSameResult(Seq(
      ("originalY (CG017)", runOriginalKernel("CGO17_ConvolutionRow_small.cl",
        N, lsY, gsY, matrix, weights)),
      ("dpiaY", runKernel(gen.OpenCLKernel(blurYTiled2DTiledLoadingTransposed(N)),
        lsY, gsY, matrix, weights))
    ))
  }
}
