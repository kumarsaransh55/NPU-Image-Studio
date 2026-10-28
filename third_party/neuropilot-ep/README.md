# Patched MediaTek ONNX Runtime NeuroPilot plugin

The app ships a **rebuilt** `libonnxruntime_providers_neuropilot.so`. It is built from MediaTek's MIT-licensed source,
https://github.com/onnxruntime/onnxruntime-ep-neuropilot, at commit `984ce9c` (v0.1.0, Oct 2026), with the fixes in
`npu-image-studio-fixes.patch`.

`libneuron_op_capability.so` is still MediaTek's prebuilt binary from the v0.1.0 release, unchanged.

## Why

With MediaTek's prebuilt v0.1.0, the Stable Diffusion 1.5 UNet could not be compiled for the NPU on the vivo X200T
(Dimensity 9400+). Each problem below was found from the phone's own logs over USB.

| # | Problem | Fix |
|---|---|---|
| 1 | **Unsqueeze/Squeeze (opset 13+) axes were read only from `int64_data`.** ONNX Runtime stores small constants in `raw_data`, so the axes came out empty and Unsqueeze silently became a no-op (`[1,320]` stayed `[1,320]` instead of `[1,320,1,1]`). The next Transpose then failed with *"Perm and input should have the same number of dimensions"* / *"perm moves a squeezed leading unit dim"* | `GetAxesForSqueezeAndUnSqueeze` uses `NeuronGraphContext::GetInt64Data` (reads `raw_data` or `int64_data`) and errors instead of guessing when axes aren't constant |
| 2 | **Gemm with a runtime C.** ONNX Runtime's MatMulAddFusion turns MatMul+Add into Gemm with a *computed* tensor as C (SD UNet: `/time_embedding/cond_proj`). The plugin passed C as the FULLY_CONNECTED bias, and MDLA only accepts constant biases, so the driver rejected the whole partition (*"Some ops unsupported by devices"*) | FULLY_CONNECTED with a zero bias, followed by a separate ADD |
| 3 | **Zero-bias length** used B's dim 0, which is wrong when `transB=0` (B is `[K, N]`). The resulting invalid operand made the driver reject every op in the partition | Bias length = last dim of the Gemm output |
| 4 | Logs went only to `stderr` (discarded on Android), and some detail was missing | Logs also go to logcat (INFO and above) and to the file named by `NEURON_EP_LOG_FILE`. `NEURON_EP_LOG_LEVEL` controls detail (0 = verbose). Build errors name the ONNX node; rejected Neuron ops are listed with their source node; Transpose errors print both shape views; a success message mislabelled as ERROR is now VERBOSE |
| 5 | Run-time failures said only `NEURON_BAD_DATA` | The error names the failing call (`setInput` / `setOutput` / `compute`), the tensor and its byte size, and lists every input/output with its size and the size the compiled network expects. A refused input is probed (page-aligned copy, other lengths) and the result logged |
| 6 | I/O buffers were always passed at the tensor's own size | The plugin asks the runtime for the expected (hardware-padded) size (`NeuronCompilation_getInputPaddedSize` / `getOutputPaddedSize`) and stages data through a bigger buffer when needed |

Result on the X200T: the whole UNet compiles for the NPU (78 partitions, 2186 of 2269 nodes on NPU/MDLA). One LCM step
at 256×256 takes ≈0.5 s, versus 1.1–1.8 s on the CPU.

Still open at 512×512: with the full-resolution attention on the CPU, all 83 partitions compile, but at run time one
of them is refused as if it was never loaded (`setInput` returns `NEURON_BAD_DATA` for any buffer). The NPU's
memory for loaded networks seems to run out at that size.

## Rebuild

1. Clone the repo and check out `984ce9c`.
2. Apply the fixes: `cd <repo>/src/neuron_plugin_ep/neuron_impl`, then `patch -p4 < npu-image-studio-fixes.patch`.
3. Configure and build:
   - CMake + Ninja.
   - Toolchain: `cmake/toolchain-android.cmake`.
   - NDK r28.
   - `-DORT_INCLUDE_DIR=<onnxruntime-android 1.31 AAR headers>`.
   - `-DNEURON_OP_CAPABILITY_LIB_DIR=<v0.1.0 release>`.
   - `-DPROTOBUF_PROTOC_EXECUTABLE=<protoc 21.12>`.
   - `"-DCMAKE_C_FLAGS=-ffile-prefix-map=<your home folder>/="` and the same for `CMAKE_CXX_FLAGS`, so the binary doesn't contain your local folder paths (they end up in log-message file names otherwise).
4. Strip the result with `llvm-strip --strip-unneeded`.
