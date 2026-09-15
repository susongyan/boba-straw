# Environment

- Baseline Core: `b9de0a0b96abb055b2fa4d14783ffd216024321a`
- Candidate Core: `2214adbd937f9f8ba61c874efb7fec25a2391cfe`
- Harness: `b9de0a0b96abb055b2fa4d14783ffd216024321a`
- Artifact preparation UTC: `2026-09-15T11:25:54Z`
- Host: macOS 15.4.1 x86_64, 8 logical CPUs
- Initial load: 8.23, 1.029/core (limit 1.50)
- Colima: Virtualization.framework, x86_64, Docker, virtiofs
- Docker: client 29.7.2 / server 29.5.2
- Java: Oracle JDK 21.0.7 LTS; Maven 3.9.6; JMH 1.37
- Redis: 7.4.2, loopback 17379, 2 CPU / 2 GiB, RDB/AOF disabled
- Image: `redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9`
- JMH: `-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc`, heap 512 MiB

Artifact SHA-256:

```text
baseline-core.jar      b50d075187a29f74cb671a62ebfe0c98b287736128f0710b39f8c669b97e5e7b
candidate-core.jar     31dd13e4f713df189252b85f9b371b6ae53bc3bbcfa517aa66a36bb282fad482
benchmarks-harness.jar 83af5cf19f0e07de54dd95aee802b12020e3ee665b3eb966e8afdd8750aa45fe
```

原始环境文件中的用户目录与本机 socket 路径未提交。
