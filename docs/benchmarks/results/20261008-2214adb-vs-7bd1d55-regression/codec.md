# codec 完整配对数据

来源：`20261007-2214adb-vs-7bd1d55-codec`，按 benchmark、参数和 mode 配对原始 JSON。定义与限制见[总报告](summary.md)。
数值显示为六位有效数字，比例四位小数；完整精度和样本在原始 JSON 中。

| Workload / 参数 | 指标 | A01 | B02 | B03 | A04 | B02/A01 | B03/A04 | 均值比变化 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| decodeBulk64  | 吞吐 ops/s | 9.31169e+06 | 9.37964e+06 | 9.25434e+06 | 9.09038e+06 | 1.0073 | 1.0180 | +1.26% |
| decodeBulk64  | allocation B/op | 120 | 120 | 120 | 120 | 1.0000 | 1.0000 | -0.00% |
| decodeBulk64KiB  | 吞吐 ops/s | 103887 | 98881.6 | 103090 | 100936 | 0.9518 | 1.0213 | -1.39% |
| decodeBulk64KiB  | allocation B/op | 65592 | 65592 | 65592 | 65592 | 1.0000 | 1.0000 | +0.00% |
| decodeFragmentedBulkByteByByte  | 吞吐 ops/s | 14759 | 14679.2 | 15185.4 | 14727 | 0.9946 | 1.0311 | +1.28% |
| decodeFragmentedBulkByteByByte  | allocation B/op | 1080.23 | 1080.23 | 1080.23 | 1080.23 | 1.0000 | 1.0000 | -0.00% |
| decodeResp3Aggregate  | 吞吐 ops/s | 2.29771e+06 | 2.20909e+06 | 2.25155e+06 | 2.32302e+06 | 0.9614 | 0.9692 | -3.46% |
| decodeResp3Aggregate  | allocation B/op | 632.002 | 632.002 | 632.002 | 632.001 | 1.0000 | 1.0000 | +0.00% |
| decodeResponseBurst128  | 吞吐 ops/s | 1.85749e+07 | 1.87546e+07 | 1.84695e+07 | 1.81866e+07 | 1.0097 | 1.0156 | +1.26% |
| decodeResponseBurst128  | allocation B/op | 64.0002 | 64.0002 | 64.0002 | 64.0002 | 1.0000 | 1.0000 | -0.00% |
| encodeGet  | 吞吐 ops/s | 1.22261e+07 | 1.23252e+07 | 1.2171e+07 | 1.21268e+07 | 1.0081 | 1.0036 | +0.59% |
| encodeGet  | allocation B/op | 144 | 144 | 144 | 144 | 1.0000 | 1.0000 | -0.00% |
