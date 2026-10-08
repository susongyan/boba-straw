# critical 完整配对数据

来源：`20261006-2214adb-vs-7bd1d55-critical`，按 benchmark、参数和 mode 配对原始 JSON。定义与限制见[总报告](summary.md)。
数值显示为六位有效数字，比例四位小数；完整精度和样本在原始 JSON 中。

| Workload / 参数 | 指标 | A01 | B02 | B03 | A04 | B02/A01 | B03/A04 | 均值比变化 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| asyncGetWindow1024 endpoint=redis://127.0.0.1:17379, protocol=AUTO | 吞吐 ops/s | 176966 | 155456 | 126457 | 195471 | 0.8784 | 0.6469 | -24.31% |
| asyncGetWindow1024 endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 1384.2 | 1487.88 | 1502.91 | 1383.67 | 1.0749 | 1.0862 | +8.05% |
| pipeline128 endpoint=redis://127.0.0.1:17379, protocol=AUTO | P99 us/op | 48.2592 | 48.768 | 68.7206 | 28.8941 | 1.0105 | 2.3784 | +52.28% |
| pipeline128 endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 1170.08 | 1216.18 | 1233 | 1170.15 | 1.0394 | 1.0537 | +4.66% |
| pipeline128 endpoint=redis://127.0.0.1:17379, protocol=AUTO | 吞吐 ops/s | 60495.7 | 51929.3 | 39700 | 67949.3 | 0.8584 | 0.5843 | -28.66% |
| pipeline128 endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 1169.15 | 1230.49 | 1215.12 | 1169.96 | 1.0525 | 1.0386 | +4.55% |
| syncGet endpoint=redis://127.0.0.1:17379, protocol=AUTO | P99 us/op | 4586.05 | 5008.42 | 5742.59 | 3444.74 | 1.0921 | 1.6671 | +33.87% |
| syncGet endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 1571.35 | 1646.81 | 1735.92 | 1620.54 | 1.0480 | 1.0712 | +5.98% |
| healthyGetDuringNoisyPipeline endpoint=redis://127.0.0.1:17379, protocol=AUTO | P99 us/op | 7864.16 | 11632.6 | 10125 | 5071.01 | 1.4792 | 1.9966 | +68.21% |
| healthyGetDuringNoisyPipeline endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 179048 | 182419 | 181421 | 176942 | 1.0188 | 1.0253 | +2.21% |
| healthyGetDuringNoisyPipeline endpoint=redis://127.0.0.1:17379, protocol=AUTO | noisyCommands # | 1.44397e+06 | 1.16621e+06 | 1.14982e+06 | 1.66106e+06 | 0.8076 | 0.6922 | -25.41% |
| healthyGetDuringSlowCallback endpoint=redis://127.0.0.1:17379, protocol=AUTO | P99 us/op | 4455.63 | 6424.08 | 4907.01 | 3461.12 | 1.4418 | 1.4178 | +43.13% |
| healthyGetDuringSlowCallback endpoint=redis://127.0.0.1:17379, protocol=AUTO | allocation B/op | 1831.28 | 2240.75 | 2151.46 | 1881.34 | 1.2236 | 1.1436 | +18.31% |
| healthyGetDuringSlowCallback endpoint=redis://127.0.0.1:17379, protocol=AUTO | noisyCompletions # | 6248 | 5625 | 6161 | 6777 | 0.9003 | 0.9091 | -9.51% |
