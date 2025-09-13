# intPlatinum MV - Android版聊天应用

这是intPlatinum聊天系统的Android移动版本，采用现代化的Material Design设计风格，支持与现有服务器的完全兼容。

## 功能特性

- 🌙 **暗黑模式UI** - 现代化的暗色主题界面
- 💬 **实时聊天** - 支持文本消息的实时发送和接收
- 🖼️ **图片分享** - 支持图片文件的发送和接收
- 👥 **用户列表** - 实时显示在线用户状态
- 🔒 **安全连接** - 与服务器建立安全的Socket连接
- 📱 **竖屏优化** - 专为移动设备优化的竖屏布局

## 技术栈

- **开发语言**: Kotlin
- **UI框架**: Android Jetpack + Material Design 3
- **网络通信**: Socket + Coroutines
- **图片加载**: Glide
- **数据解析**: Gson
- **架构模式**: MVVM

## 项目结构

```
app/
├── src/main/
│   ├── java/com/intplatinum/mv/
│   │   ├── data/           # 数据模型
│   │   ├── network/        # 网络通信
│   │   ├── ui/            # 用户界面
│   │   │   └── adapter/   # RecyclerView适配器
│   │   └── IntPlatinumApplication.kt
│   ├── res/
│   │   ├── layout/        # 布局文件
│   │   ├── values/        # 资源文件
│   │   └── drawable/      # 图标资源
│   └── AndroidManifest.xml
└── build.gradle
```

## 构建要求

- Android Studio Arctic Fox 或更高版本
- Android SDK 34
- 最低支持 Android 7.0 (API 24)
- Kotlin 1.8.0+

## 安装和运行

1. 克隆项目到本地
2. 使用Android Studio打开项目
3. 等待Gradle同步完成
4. 连接Android设备或启动模拟器
5. 点击运行按钮构建并安装应用

## 服务器配置

应用默认连接到 `localhost:7995`，你可以在登录界面修改服务器地址。

确保服务器端已启动并运行在指定端口上。

## 使用说明

1. **登录**: 输入服务器地址和用户名，点击连接
2. **发送消息**: 在输入框中输入文本，点击发送按钮
3. **发送图片**: 点击附件按钮选择图片发送
4. **查看用户**: 点击右上角用户列表按钮查看在线用户
5. **返回**: 点击返回按钮断开连接并退出

## 兼容性

本Android版本完全兼容现有的intPlatinum服务器协议，支持：

- 客户端版本验证 (v1.0.0b)
- Base64加密通信
- 文本和图片消息
- 用户状态同步
- 系统消息通知

## 开发说明

项目采用现代Android开发最佳实践：

- 使用ViewBinding进行视图绑定
- 使用Kotlin Coroutines处理异步操作
- 使用Flow进行数据流管理
- 遵循Material Design设计规范
- 支持暗黑模式主题