# JTools HTTPS

一个运行在 IntelliJ 平台里的轻量 HTTP Client 插件，支持接口列表管理、调用记录与响应查看。

## 功能概览

- 接口列表与分组管理（保存、重命名、删除）
- 调用标签页（多 Tab 发送请求）
- 请求历史与响应详情展示
- 响应渲染（原始/JSON/XML/HTML/图片）
- 一键复制 cURL
- 自动识别本地服务端口并生成请求地址

## 使用手册

### 1. 打开工具窗口

- 在 IDE 右侧找到 **HTTP Client** 工具窗口（或 `View | Tool Windows | HTTP Client`）。

### 2. 生成/创建请求

- 在 Spring Controller 方法左侧行号区域点击小图标，或右键 **添加到调用列表**，自动生成请求。
- 点击 **新建** 手动创建请求，选择方法并输入 URL。

### 3. 填写请求信息

- 在 **路径变量/参数/请求头/请求体/Cookie** 标签中填写数据。
- 请求体支持：**无 / JSON / x-www-form-urlencoded / form-data**。

### 4. 发送与查看响应

- 点击 **发送** 发起请求。
- 响应区支持 **原始/渲染/响应头/请求头/请求信息** 视图。
- JSON/XML/HTML/图片可自动渲染，二进制响应可 **保存文件**。

### 5. 保存与复用

- 点击 **保存接口** 保存到左侧接口列表。
- 接口列表支持 **分组、搜索、拖拽排序**。
- **历史请求** 查看全局调用记录，**历史** 查看当前请求记录。

### 6. 小技巧

- **复制 cURL**：点击 **复制 cURL**。
- **端口识别**：运行本地服务后自动识别端口并填充 URL，未识别时默认 8080。

## 构建

```bash
# jtools版本
./gradlew assemble
# idea版本
./gradlew buildPlugin
```

产物为 shadowJar：

```
build/libs/jtools-https.jar
```

## 版本

- 当前版本：0.0.2

### v0.0.2

- 新增前置脚本与后置脚本，可在请求发送前后动态处理 URL、参数、请求头、Cookie 和响应内容。
- 新增脚本环境变量管理（项目级/全局级），支持在脚本中通过 `env` / `store` 读取与写入。
- 新增脚本使用辅助能力：API 说明、示例脚本、片段插入，支持彩色图标入口和片段选择。
- 新增 `jvm` bridge，可通过 `jvm.type("全限定类名")` 调用项目依赖库静态方法（例如 AES 工具类）。
- 脚本引擎增强：支持 ES6 模板字符串（反引号插值），并兼容 `log.info` / `log.warn` / `log.error` 写法。

#### 脚本调用项目依赖示例（v0.0.2）

```javascript
var AES = jvm.type("com.company.common.crypto.AES");
var encryptor = AES.ECB.buildEncrypt(env.get("key"));
var result = encryptor.getBase64();
vars.cipher = result && result.data ? result.data : String(result);
log.info(`cipher=${vars.cipher}`);
```

### v0.0.1

- 首次发布，提供接口扫描、请求历史、响应渲染、cURL 复制等基础能力。
