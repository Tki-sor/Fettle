# 03 · 本机构建环境实测笔记

> 记录在**这台机器**上构建 Fettle 时踩到的两个环境坑与解决办法。都是实测出来的，不是推测。

## 1. Gradle 守护进程会跑到 JDK 25，导致构建脚本编译失败

**症状**：
```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_' Unsupported class file major version 69
```
（class file major 69 = Java 25）

**根因**（`./gradlew --version` 直接暴露）：
```
Launcher JVM:  21.0.10 (Oracle Corporation ...)
Daemon JVM:    C:\Program Files\Java\jdk-25.0.2 (from org.gradle.java.home)
```
**用户级** `~/.gradle/gradle.properties` 里设了 `org.gradle.java.home=jdk-25.0.2`。Gradle 的守护进程因此跑在 Java 25 上，而 Gradle 8.14.3 自带的 Groovy 读不了 major 69 的类文件。
注意：项目自己的 toolchain 是 21 也没用 —— 守护进程 JVM 与 toolchain 是两件事。

**解决**：命令行显式覆盖（**用户级 Gradle 属性优先于项目级**，所以必须走 CLI）：
```bash
./gradlew build -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21.0.10"
```

## 2. Gradle 不读环境变量代理，连不上 maven.neoforged.net

**症状**：
```
Could not GET 'https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/...pom'
> Connection timed out: getsockopt
```

**根因**：本机网络走本地代理（`http_proxy=http://127.0.0.1:7890`），`curl` 会读这些环境变量（实测 `maven.neoforged.net` 经代理返回 **200**），但 **Gradle/JVM 默认不读 `http_proxy` 环境变量**，于是直连超时。

**解决**：给 Gradle 传标准 JVM 代理属性：
```bash
-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 -Dhttp.nonProxyHosts=localhost
```

## 3. 本机可用的完整构建命令

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew build --no-daemon --console=plain \
  -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21.0.10" \
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 \
  -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
  -Dhttp.nonProxyHosts=localhost
```

> 如果不想每次敲这么长，可以把这两条写进 `~/.gradle/gradle.properties`：
> ```
> org.gradle.java.home=C:\\Program Files\\Java\\jdk-21.0.10
> systemProp.http.proxyHost=127.0.0.1
> systemProp.http.proxyPort=7890
> systemProp.https.proxyHost=127.0.0.1
> systemProp.https.proxyPort=7890
> ```
> 注意 `systemProp.*` 是**全局**生效，会影响你机器上所有 Gradle 项目（对本机的 MC 工作都是有益的，但对其它 Java 项目可能不合适）。

## 4. 第一次构建会下载什么（已实测）

| 项 | 说明 |
| --- | --- |
| Gradle 8.14.3 发行包 | 由 wrapper 从 `services.gradle.org` 下载（已在本次构建中完成） |
| ModDevGradle 插件 2.0.107 | 从 Gradle Plugin Portal |
| `neoform-runtime` | MDG 的 Minecraft 处理管线依赖（**这是之前卡住的下载项**） |
| NeoForge 21.1.227 userdev / sources / universal | 本地缓存里已有部分 |
| Minecraft 1.21.1 client/server jar | 本地 `neoformruntime/artifacts` 已有 |

## 5. 对基准协议的影响

[01-benchmark-and-charts.md](01-benchmark-and-charts.md) §1 要求的 "JDK 21" 现在有了**具体的坑**：不是装了 JDK 21 就行，必须确认**守护进程 JVM 也是 21**（`./gradlew --version` 里看 `Daemon JVM` 一行）。
同理，该文档 §8 的 "未实测项" 应补一条：**Gradle 侧的网络必须走代理参数**，否则依赖解析会以超时形式失败。
