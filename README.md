# 票记 PiaoJi

基于多模态大模型的 Android 智能记账应用。拍照或分享微信、支付宝等支付截图，应用调用视觉语言模型提取商家、金额、时间和分类，写入本地数据库，并生成月度统计、消费洞察与 AI 财报分析。

> 本项目源自《软件新技术实训》课程实践，仓库仅包含应用源码，不包含课程报告、个人测试截图或任何 API Key。

## 功能特性

- **三种导入方式**：拍照记账、相册多选、从其他应用分享单图/多图。
- **截图结构化识别**：一次识别商家、实付金额、交易时间与消费分类。
- **长截图批量识别**：一张微信/支付宝长截图中的多条交易可批量提取、逐条编辑、删除后统一保存。
- **月度统计首页**：总支出、笔数、日均、最大单笔、分类环形图、每日趋势图、账单列表与月份切换。
- **AI 消费洞察**：基于本地统计事实生成一句总结与三条可执行建议。
- **AI 财报分析**：支持日、周、月、年四种周期，输出健康分、总体概览、结构分析、风险提示和下月建议。
- **多供应商模型配置**：内置常见 OpenAI 兼容供应商预设，支持自定义 BaseURL、模型名与连接测试，配置可保存、加载和删除。
- **Material You 界面**：动态取色、页面淡入与列表动效，适配 Android 8.0 及以上。

## 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Java 17 |
| 构建 | Gradle 8.9、Android Gradle Plugin 8.7.3 |
| SDK | compileSdk 35、minSdk 26、targetSdk 35 |
| UI | Android 原生 View、Material Components 1.12.0、RecyclerView、ConstraintLayout |
| 网络 | OkHttp 4.12.0，OpenAI 兼容 `/chat/completions` |
| 数据 | SQLiteOpenHelper、SharedPreferences、org.json |
| 图片 | BitmapFactory、自研采样压缩与长图切段 |
| 并发 | ExecutorService、ExecutorCompletionService |

## 识别策略

针对真实截图场景，项目采用“整图优先、超长并行分段”的混合策略：

1. 普通手机截图（不超过 600 万像素且高度不超过 4000px）整图发送一次请求，上下文完整、速度更快。
2. 超过阈值的长截图切段后以 3 路并发识别，切片之间保留约 14% 重叠，最后按“日期 + 金额 + 商家”去重。
3. JPEG 原图且无 EXIF 旋转、体积不超过 6MB、像素不超过 2400 万时直接上传原始字节，省去一次解码与重新编码；其他格式或超大图才压缩。

实测在 Xiaomi 2211133C（Android 16）与 DashScope `qwen3.8-flash` 下：1080×2400 截图整图识别约 16–35 秒；1080×12160 长截图 8 段并行约 119 秒，识别 49 笔。具体耗时受模型供应商与网络状况影响。

## 项目结构

```
app/src/main/java/com/demo/ledger/
├── App.java                     Application 初始化、动态取色、IO/UI 线程调度
├── data/
│   ├── Bill.java                账单实体与分类枚举
│   ├── BillDbHelper.java        SQLite 建库建表
│   ├── BillRepository.java      账单写入与按月/区间聚合
│   ├── ModelProfile.java        命名模型配置
│   └── Prefs.java               模型参数与配置持久化
├── net/
│   ├── LlmClient.java           OpenAI 兼容请求、整图/分段识别、错误映射
│   └── LlmParser.java           账单、洞察、财报 JSON 容错解析
├── ui/
│   ├── MainActivity.java        首页统计、图表与账单列表
│   ├── DonutView.java           分类环形图
│   ├── TrendChartView.java      每日趋势图
│   ├── ConfirmActivity.java     单图识别确认
│   ├── ShareActivity.java       接收系统分享
│   ├── BatchActivity.java       批量识别、编辑与保存
│   ├── InsightActivity.java     AI 消费洞察
│   ├── ReportActivity.java      AI 财报分析
│   ├── SettingsActivity.java    模型配置
│   └── TutorialActivity.java    模型接入教程
└── util/
    ├── ImageCompressor.java     采样解码、EXIF 纠正与压缩
    ├── ImageTiles.java          长图切段
    └── UriFiles.java            分享/相册图片拷贝
```

## 快速开始

### 环境要求

- Android Studio（建议 Ladybug 及以上）
- JDK 17
- Android SDK 35
- 一台 Android 8.0（API 26）及以上的设备或模拟器

### 构建与安装

```bash
git clone https://github.com/TheArbor/piaoji-ledger.git
cd piaoji-ledger
./gradlew :app:assembleDebug
```

Windows 使用：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，可直接安装到设备。

### 配置模型

1. 打开应用左上角“设置”；
2. 选择供应商预设，或选择“自定义”填写 BaseURL；
3. 填写 API Key 与模型名；
4. 点击“测试连接”，确认通过后保存。

项目使用 OpenAI 兼容协议，支持 DashScope、智谱 GLM、Kimi、火山方舟等提供兼容接口的服务，也可以接入自建网关。API Key 只保存在设备本地的 SharedPreferences 中，不会写入仓库或上传到第三方服务器（识别请求会直接发送到用户配置的模型服务）。

## 隐私说明

- 账单数据保存在应用私有目录中的 SQLite 数据库；
- 导入的图片会拷贝到应用私有目录，识别请求以 base64 图片形式直接发往用户配置的模型接口；
- 仓库不包含任何真实账单截图、API Key 或个人信息；
- 截图可能包含消费记录等敏感信息，分享或提交 Issue 前请先脱敏。

## 已知限制

- 识别依赖云端多模态模型，网络状况与供应商排队会明显影响耗时；
- 超长截图仍需分段并行识别，无法保证单次请求在固定时间内返回；
- 当前以真机人工测试为主，尚未建立自动化测试；
- 不包含账号体系、云同步和多端协作；
- 模型输出经过容错解析，但极端情况下仍可能出现字段缺失，需要人工确认。

## 许可证

本项目采用 [MIT License](LICENSE)。

## 致谢

感谢课程实训过程中提供的真机调试条件，以及 DashScope 等 OpenAI 兼容模型服务为联调提供的支持。
