# JTools HTTPS

一个运行在 IntelliJ 平台里的轻量 HTTP Client 插件，支持接口列表管理、调用记录与响应查看。

## 功能概览

- 接口列表与分组管理（保存、重命名、删除）
- 调用标签页（多 Tab 发送请求）
- 请求历史与响应详情展示
- 响应渲染（原始/JSON/XML/HTML/图片）
- 一键复制 cURL
- 自动识别本地服务端口并生成请求地址

## 构建

```bash
./gradlew assemble
```

产物为 shadowJar：

```
build/libs/jtools-https.jar
```

## 版本

- 当前版本：0.0.1
