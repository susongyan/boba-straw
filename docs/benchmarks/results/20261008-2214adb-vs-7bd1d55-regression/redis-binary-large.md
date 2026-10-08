# redis-binary-large 完整配对数据

来源：`20261006-2214adb-vs-7bd1d55-redis-binary-large`，按 benchmark、参数和 mode 配对原始 JSON。定义与限制见[总报告](summary.md)。
数值显示为六位有效数字，比例四位小数；完整精度和样本在原始 JSON 中。

| Workload / 参数 | 指标 | A01 | B02 | B03 | A04 | B02/A01 | B03/A04 | 均值比变化 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | P99 us/op | 4117.87 | 4530.18 | 3731.46 | 2868.59 | 1.1001 | 1.3008 | +18.25% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | allocation B/op | 2746.16 | 2830.86 | 2807.82 | 2699.45 | 1.0308 | 1.0401 | +3.55% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | P99 us/op | 35098.5 | 19827.3 | 19626.1 | 22904.8 | 0.5649 | 0.8569 | -31.98% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05283e+06 | 1.0529e+06 | 1.05282e+06 | 1.05265e+06 | 1.0001 | 1.0002 | +0.01% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | P99 us/op | 8001.13 | 4861.38 | 6914.05 | 4180.95 | 0.6076 | 1.6537 | -3.34% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | allocation B/op | 67389.4 | 67423 | 67696.8 | 67279.8 | 1.0005 | 1.0062 | +0.33% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | 吞吐 ops/s | 712.697 | 769.578 | 755.465 | 821.14 | 1.0798 | 0.9200 | -0.57% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | allocation B/op | 2611.2 | 2668.1 | 2734.57 | 2663.72 | 1.0218 | 1.0266 | +2.42% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | 吞吐 ops/s | 82.8782 | 71.8163 | 87.1825 | 90.7307 | 0.8665 | 0.9609 | -8.42% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05215e+06 | 1.05234e+06 | 1.05238e+06 | 1.05218e+06 | 1.0002 | 1.0002 | +0.02% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | 吞吐 ops/s | 442.389 | 346.594 | 428.31 | 465.896 | 0.7835 | 0.9193 | -14.69% |
| get endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | allocation B/op | 67158.4 | 67308.3 | 67282.7 | 67165.1 | 1.0022 | 1.0018 | +0.20% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | P99 us/op | 4139.05 | 2998.27 | 3407.87 | 2750.26 | 0.7244 | 1.2391 | -7.01% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | allocation B/op | 2745.33 | 2864.16 | 2874.37 | 2692.15 | 1.0433 | 1.0677 | +5.54% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | P99 us/op | 27995.7 | 20231 | 18952.4 | 20202.8 | 0.7226 | 0.9381 | -18.70% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05219e+06 | 1.05228e+06 | 1.05227e+06 | 1.05198e+06 | 1.0001 | 1.0003 | +0.02% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | P99 us/op | 8772.81 | 6262.78 | 4349.95 | 4941.33 | 0.7139 | 0.8803 | -22.61% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | allocation B/op | 67506.9 | 67606 | 67448.7 | 67424.4 | 1.0015 | 1.0004 | +0.09% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | 吞吐 ops/s | 604.829 | 617.203 | 755.549 | 802.009 | 1.0205 | 0.9421 | -2.42% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1024 | allocation B/op | 2607.12 | 2730.75 | 2700.56 | 2712.01 | 1.0474 | 0.9958 | +2.11% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | 吞吐 ops/s | 82.4199 | 57.069 | 80.8095 | 85.7182 | 0.6924 | 0.9427 | -18.00% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.0515e+06 | 1.05175e+06 | 1.05172e+06 | 1.05149e+06 | 1.0002 | 1.0002 | +0.02% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | 吞吐 ops/s | 473.007 | 303.525 | 444.383 | 479.226 | 0.6417 | 0.9273 | -21.46% |
| set endpoint=redis://127.0.0.1:17379, protocol=AUTO, valueBytes=65536 | allocation B/op | 67266.3 | 67412.9 | 67327.5 | 67266.3 | 1.0022 | 1.0009 | +0.15% |
