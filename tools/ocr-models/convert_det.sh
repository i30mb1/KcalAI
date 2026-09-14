#!/bin/bash
# Детектор под другие размеры входа (ВЫСОТАxШИРИНА). ONNX уже лежит в models_tmp.
set -e
cd /work
SIZES="${SIZES:-1280x960,960x736}"
mkdir -p out_det

python3 - "$SIZES" <<'EOF'
import sys, onnx, numpy as np
from onnxsim import simplify
import onnx_graphsurgeon as gs

def decompose_hardsigmoid(graph, node):
    alpha = node.attrs.get("alpha", 0.2); beta = node.attrs.get("beta", 0.5)
    c = lambda n, v: gs.Constant(f"{node.name}_{n}", np.array([v], dtype=np.float32))
    mul_out = gs.Variable(f"{node.name}_mul_out"); add_out = gs.Variable(f"{node.name}_add_out"); max_out = gs.Variable(f"{node.name}_max_out")
    graph.nodes.extend([
        gs.Node("Mul", f"{node.name}_mul", inputs=[node.inputs[0], c("alpha", alpha)], outputs=[mul_out]),
        gs.Node("Add", f"{node.name}_add", inputs=[mul_out, c("beta", beta)], outputs=[add_out]),
        gs.Node("Max", f"{node.name}_max", inputs=[add_out, c("zero", 0.0)], outputs=[max_out]),
        gs.Node("Min", f"{node.name}_min", inputs=[max_out, c("one", 1.0)], outputs=[node.outputs[0]]),
    ])
    node.outputs.clear()

for size in sys.argv[1].split(","):
    h, w = map(int, size.split("x"))
    model = onnx.load("models_tmp/ocr_det_v5.onnx")
    ms, ok = simplify(model, overwrite_input_shapes={"x": [1, 3, h, w]}, perform_optimization=True)
    print(size, "simplify", ok)
    if ok: model = ms
    graph = gs.import_onnx(model)
    for n in [n for n in graph.nodes if n.op == "HardSigmoid"]:
        decompose_hardsigmoid(graph, n)
    graph.cleanup().toposort()
    model = gs.export_onnx(graph)
    for node in model.graph.node:
        if node.op_type == "Resize":
            for attr in node.attribute:
                if attr.name == "coordinate_transformation_mode" and attr.s == b"half_pixel":
                    attr.s = b"asymmetric"
    onnx.save(model, f"models_tmp/det_{h}x{w}.onnx")
EOF

IFS=',' read -ra ARR <<< "$SIZES"
for size in "${ARR[@]}"; do
  H="${size%x*}"; W="${size#*x}"
  echo "=== onnx2tf $H x $W"
  rm -rf "models_tmp/conv_det_${H}x${W}"
  onnx2tf -i "models_tmp/det_${H}x${W}.onnx" -o "models_tmp/conv_det_${H}x${W}" -b 1 -ois "x:1,3,${H},${W}" -n > "out_det/onnx2tf_${H}x${W}.log" 2>&1 || { tail -30 "out_det/onnx2tf_${H}x${W}.log"; exit 1; }
  mkdir -p "out_det/${H}x${W}"
  cp "$(find "models_tmp/conv_det_${H}x${W}" -name '*float16.tflite' | head -1)" "out_det/${H}x${W}/ocr_det_fp16.tflite"
  cp "$(find "models_tmp/conv_det_${H}x${W}" -name '*float32.tflite' | head -1)" "out_det/${H}x${W}/ocr_det_fp32.tflite"
  python3 - "out_det/${H}x${W}/ocr_det_fp16.tflite" <<'EOF'
import sys, os, hashlib, tensorflow as tf
p = sys.argv[1]
it = tf.lite.Interpreter(model_path=p); it.allocate_tensors()
print(p, os.path.getsize(p), hashlib.sha256(open(p, "rb").read()).hexdigest())
print("  in ", [list(i["shape"]) for i in it.get_input_details()], " out", [list(o["shape"]) for o in it.get_output_details()])
EOF
done
echo "=== DONE ==="
