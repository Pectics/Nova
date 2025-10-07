# Nova Kotlin 运行库依赖分析

## 现有构建与加载流程
- `nova` 模块通过 `novaLoaderApi` 与 `novaLoader` 两个配置来声明需要被打入发布构建的依赖，其中包括整套 Kotlin 运行库（`kotlin-stdlib`、`kotlin-reflect`、`kotlinx-coroutines` 等）。这些依赖在 `build.gradle.kts` 中被直接加入 `novaLoaderApi`，因此既参与编译，又会被标记为要随发布包分发。 【F:nova/build.gradle.kts†L1-L38】
- 自定义的 `BundlerPlugin` 会让 `novaLoaderApi` 继承到 `api`，`novaLoader` 继承到 `implementation`，从而确保所有 Kotlin 相关依赖最终出现在 `novaLoader` 配置中。 【F:buildSrc/src/main/kotlin/BundlerPlugin.kt†L1-L12】
- `BuildBundlerJarTask` 会在构建最终的 `Nova-<version>.jar` 时，把 `novaLoader` 配置中的每一个依赖原样封装到 `lib/<group>/<artifact>/<version>/<jar>` 结构中，并在运行时通过 `NovaLoader` 再次把这些 JAR 以 `JarLibrary` 的形式添加到插件类路径。 【F:buildSrc/src/main/kotlin/BuildBundlerJarTask.kt†L42-L65】【F:nova/src/main/kotlin/xyz/xenondevs/nova/NovaLoader.java†L19-L43】

该流程确保了 Nova 在没有外部插件时可以独立运行，但它也意味着 Kotlin 运行库是以“额外 JAR”形式按插件单独加载的。

## 已出现的问题
- 如果某个 Nova Addon（如 Machines）同样通过 bundler 机制打包 Kotlin 运行库，那么 Paper 将为 Addon 创建一个新的插件类加载器，该加载器会从自身的 `lib/` 目录再次加载 `org.jetbrains.kotlin` 下的所有类。
- 当 Addon 与 Nova 彼此调用 Kotlin 运行库中的同一个类（例如 `kotlin.jvm.internal.PropertyReference0`）时，就会出现“同名不同类加载器”的情形。上游报错中的 `loader constraint violation` 正是因为 Nova 与 Addon 分别加载出了不同的 `PropertyReference0` 类对象，导致 JVM 无法把它们视作同一个类型。
- 由于 Kotlin 运行库无法安全地做包重定位（relocation），当前的“每个插件打包一份 Kotlin JAR”策略就容易在多插件环境中产生上述冲突。

## 可能的解决方案
1. **把 Kotlin 运行库转成可共享的依赖提供者**
   - 新建一个只包含 Kotlin 运行库的“Runtime”插件，或把现有 Nova 运行库拆分为一个单独子模块。Nova 与各个 Addon 在 `plugin.yml`/`paper-plugin.yml` 中声明 `depend`/`loadbefore`，让 Bukkit 在解析类时先从运行库插件的类加载器中查找。这样 Kotlin 类只会加载一次。
   - 一旦运行库插件提供了类加载器，Nova 主体与 Addon 的构建脚本都可以把 Kotlin 相关依赖改成 `compileOnly`/`runtimeOnly`，避免被 `BuildBundlerJarTask` 打进 `lib/`。

2. **在构建阶段强制所有 Nova Addon 复用 Nova 的 Kotlin 依赖**
   - 为 bundler 插件增加过滤逻辑：识别 `org.jetbrains.kotlin` 相关的构件并从 `lib/` 目录排除，同时在 Gradle 插件里自动为 Addon 添加 `compileOnly("org.jetbrains.kotlin:kotlin-stdlib:<version>")` 等依赖，避免开发者手动打包。
   - 配合修改 Addon 使用的 `PluginLoader`，在 `classpathBuilder` 上显式注入 Nova 已经解压到 `libraries/` 目录中的 Kotlin JAR（例如通过服务发现或在 `LaunchEntryPoint` 中共享注册信息），确保运行时仍能解析到这些类。

3. **利用 Paper 的全局库机制**
   - 把 Kotlin 运行库发布到 Paper 的 `libraries/` 目录（或使用 `paper-plugin.yml` 的 `libraries:` 字段指向公共 Maven 仓库），让 Paper 在服务启动阶段一次性加载。Nova 与 Addon 仅以 `compileOnly` 方式声明 Kotlin 依赖即可。由于这类库由 Paper 的全局类加载器负责，所有插件都会拿到相同的 `Class` 对象。

上述方案都能避免“每个插件各自加载一份 Kotlin”导致的类冲突。第一种方案对控制力最强，但需要多一个运行库插件；第二种方案能保持现有打包格式，但需要增强 bundler；第三种方案依赖服务器运营者接受外部库下载。可以根据部署环境与维护成本选择其一或结合使用。

### 第三方案的落地策略

Paper 自 `paper-plugin.yml` 起支持在 `libraries:` 字段中声明 Maven 坐标，服务器会在启动时下载这些依赖并放入全局类加载器，前提是依赖所在仓库对服务器可见。为了兼顾“尽量复用 Paper 的托管”与“保留对非公共仓库依赖的控制”，可以按以下步骤拆分：

1. **在构建脚本中显式区分“Paper 托管”与“需本地打包”的依赖**
   - 引入新的 Gradle configuration（例如 `paperRuntime`）承载所有来自 Maven Central 或其他公共仓库的 Kotlin 依赖，并在 `paper-plugin.yml` 生成流程中把这些坐标写入 `libraries:`。
   - 原有 `novaLoader` 配置只保留必须随插件一同分发的闭源/私有库（例如自建仓库或发布受限的 SDK），并继续通过 `BuildBundlerJarTask` 打包。

2. **对仍需打包的依赖限制作用范围，避免再次引入 Kotlin 冲突**
   - 为 bundler 增加白名单/黑名单逻辑：默认排除 `org.jetbrains.kotlin`、`org.jetbrains.kotlinx` 等命名空间，确保 Kotlin 运行库永远走 Paper 托管路径。
   - 对真的必须打包的私有库，可选择继续以 JAR 的形式放入 `lib/`，或者在构建阶段直接解压为 `classes/` 平铺到插件 JAR 中，从而避免 Paper 在解析 `lib/` 时重复加载（后者适用于只有少量类的自研工具包）。

3. **为非公共仓库提供镜像或下载提示**
   - 如果私有依赖所在的仓库可以通过 HTTP 公开访问，可在 `paper-plugin.yml` 的 `libraries:` 条目中使用 `repository@group:artifact:version` 语法指向该仓库，同时在 README 中注明需要在服务器的 `paper-global-libraries` 配置中预先声明认证信息。
   - 若仓库完全内网可见，则仍按第二步继续打包；另外可以在插件启动时检测缺失的类并给出日志提示，帮助服务器管理员定位需要手动部署的库文件。

通过上述拆分，Nova 及其 Addon 可以把 Kotlin 等公共依赖迁移到 Paper 托管，彻底避免重复加载；而来自非公共 Maven 仓库的依赖仍然能保留原有 bundler 流程或以平铺 class 的形式并入插件，既兼顾了可用性，也减轻了维护成本。
