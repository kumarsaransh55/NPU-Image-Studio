# Additional permission under GNU GPL version 3, section 7

NPU Image Studio is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
License as published by the Free Software Foundation, version 3 (see [`LICENSE`](LICENSE)), with the following
additional permission.

Copyright (C) 2026 Saransh Kumar

If you modify this Program, or any covered work, by linking or combining it with MediaTek NeuroPilot libraries (or a
modified version of those libraries), containing parts covered by the terms of their licences, the licensors of this
Program grant you additional permission to convey the resulting work.

"MediaTek NeuroPilot libraries" means `libneuron_op_capability.so` (shipped with MediaTek's ONNX Runtime NeuroPilot
plugin) and the MediaTek Neuron runtime (for example `libneuronusdk_adapter.mtk.so`, or the copy that comes with the
phone's operating system).

## Why this is here

Two parts the app uses come from MediaTek only as compiled binaries, without source code. The GPL normally requires
the source of everything shipped together with the program. This permission makes it clear that you may still share
the app, and versions you change, together with those MediaTek parts. Everything else in this repository stays under
GPL-3.0 (or, for third-party parts, their own licences: see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)).
