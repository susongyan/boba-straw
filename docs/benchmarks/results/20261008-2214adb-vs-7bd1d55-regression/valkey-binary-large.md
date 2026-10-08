# valkey-binary-large 完整配对数据

来源：`20261007-2214adb-vs-7bd1d55-valkey-binary-large`，按 benchmark、参数和 mode 配对原始 JSON。定义与限制见[总报告](summary.md)。
数值显示为六位有效数字，比例四位小数；完整精度和样本在原始 JSON 中。

| Workload / 参数 | 指标 | A01 | B02 | B03 | A04 | B02/A01 | B03/A04 | 均值比变化 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | P99 us/op | 1868.23 | 2375.11 | 2236.42 | 2400.26 | 1.2713 | 0.9317 | +8.04% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | allocation B/op | 2778.09 | 2816.67 | 2728.4 | 2745.09 | 1.0139 | 0.9939 | +0.40% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | P99 us/op | 14245.6 | 16348 | 16502.3 | 17103.9 | 1.1476 | 0.9648 | +4.79% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05258e+06 | 1.05287e+06 | 1.05284e+06 | 1.05264e+06 | 1.0003 | 1.0002 | +0.02% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | P99 us/op | 2969.11 | 3571.71 | 3688.45 | 3856.14 | 1.2030 | 0.9565 | +6.37% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | allocation B/op | 67235.4 | 67365.5 | 67369.5 | 67268.9 | 1.0019 | 1.0015 | +0.17% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | 吞吐 ops/s | 776.112 | 1010.72 | 860.145 | 814.494 | 1.3023 | 1.0560 | +17.62% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | allocation B/op | 2614.53 | 2714.9 | 2710.93 | 2663.84 | 1.0384 | 1.0177 | +2.79% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | 吞吐 ops/s | 43.3826 | 104.219 | 89.4681 | 88.8554 | 2.4023 | 1.0069 | +46.47% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05227e+06 | 1.05245e+06 | 1.05244e+06 | 1.05223e+06 | 1.0002 | 1.0002 | +0.02% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | 吞吐 ops/s | 526.052 | 564.115 | 475.801 | 450.007 | 1.0724 | 1.0573 | +6.54% |
| get endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | allocation B/op | 67163.8 | 67219.4 | 67249.4 | 67156.4 | 1.0008 | 1.0014 | +0.11% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | P99 us/op | 1951.05 | 2314.24 | 2545.3 | 2355.2 | 1.1862 | 1.0807 | +12.85% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | allocation B/op | 2783.64 | 2777.07 | 2767.78 | 2796.18 | 0.9976 | 0.9898 | -0.63% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | P99 us/op | 14526.7 | 16588.6 | 16744.4 | 17818.6 | 1.1419 | 0.9397 | +3.05% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05185e+06 | 1.05221e+06 | 1.05219e+06 | 1.05203e+06 | 1.0003 | 1.0002 | +0.02% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | P99 us/op | 3297.28 | 3784.7 | 4857.86 | 3946.46 | 1.1478 | 1.2309 | +19.31% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | allocation B/op | 67294.6 | 67479.8 | 67476.3 | 67317.2 | 1.0028 | 1.0024 | +0.26% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | 吞吐 ops/s | 700.399 | 931.844 | 848.427 | 842.639 | 1.3304 | 1.0069 | +15.37% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1024 | allocation B/op | 2724.52 | 2655.64 | 2690.56 | 2669.26 | 0.9747 | 1.0080 | -0.88% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | 吞吐 ops/s | 105.926 | 98.0493 | 83.3787 | 71.9705 | 0.9256 | 1.1585 | +1.99% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=1048576 | allocation B/op | 1.05148e+06 | 1.0517e+06 | 1.05171e+06 | 1.05151e+06 | 1.0002 | 1.0002 | +0.02% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | 吞吐 ops/s | 626.623 | 567.071 | 505.045 | 464.404 | 0.9050 | 1.0875 | -1.73% |
| set endpoint=redis://127.0.0.1:17380, protocol=AUTO, valueBytes=65536 | allocation B/op | 67263.8 | 67265.6 | 67300.7 | 67330.5 | 1.0000 | 0.9996 | -0.02% |
