#!/bin/bash

# Logan Android SDK - 清理并发布 Release AAR 脚本
# 此脚本执行完整的构建和发布流程，使用 maven.gradle 配置

set -e  # 遇到错误立即退出

echo "========================================="
echo "Logan Android SDK - 发布 Release AAR"
echo "========================================="
echo ""

# 检查必要文件是否存在
if [ ! -f "maven.gradle" ]; then
    echo "❌ 错误: 未找到 maven.gradle 文件"
    exit 1
fi

if [ ! -f "logan/build.gradle" ]; then
    echo "❌ 错误: 未找到 logan/build.gradle 文件"
    exit 1
fi

# 读取版本信息
VERSION=$(grep "versionCode=" gradle.properties | cut -d'=' -f2)
echo "📦 当前版本: $VERSION"
echo ""

# 1. 清理项目
echo "[1/4] 清理项目..."
./gradlew clean
if [ $? -ne 0 ]; then
    echo "❌ 清理失败！"
    exit 1
fi
echo "✅ 清理完成"
echo ""

# 2. 编译 Release AAR
echo "[2/4] 编译 Release AAR..."
./gradlew :logan:assembleRelease
if [ $? -ne 0 ]; then
    echo "❌ 编译失败！"
    exit 1
fi
echo "✅ 编译完成"
echo ""

# 3. 验证 AAR 文件是否存在
AAR_PATH="logan/build/outputs/aar/logan-release-${VERSION}.aar"
echo "[3/4] 验证 AAR 文件..."
if [ -f "$AAR_PATH" ]; then
    AAR_SIZE=$(ls -lh "$AAR_PATH" | awk '{print $5}')
    echo "✅ AAR 文件生成成功: $AAR_PATH"
    echo "   文件大小: $AAR_SIZE"
else
    echo "⚠️  警告: 未找到预期文件 $AAR_PATH"
    echo "   尝试查找其他 AAR 文件..."
    find logan/build/outputs/aar -name "*.aar" 2>/dev/null || echo "   未找到任何 AAR 文件"
fi
echo ""

# 4. 发布到远程仓库（通过 maven.gradle）
echo "[4/4] 发布到远程 Nexus 仓库..."
echo "   使用 maven.gradle 中的 uploadRemoteNew 任务"
./gradlew uploadRemoteNew
if [ $? -ne 0 ]; then
    echo "❌ 发布失败！"
    echo ""
    echo "💡 提示: 如需发布到本地仓库进行测试，请运行:"
    echo "   ./gradlew uploadLocal"
    exit 1
fi
echo "✅ 发布完成"
echo ""

echo "========================================="
echo "🎉 发布成功完成！"
echo "========================================="
echo ""
echo "📦 发布信息:"
echo "   版本: $VERSION"
echo "   AAR 文件: $AAR_PATH"
echo "   Maven GroupId: cn.chesupai.android"
echo "   Maven ArtifactId: logan"
echo ""
echo "📍 仓库地址:"
if [[ $VERSION == *SNAPSHOT* ]]; then
    echo "   Snapshots: http://nexus.dns.guazi.com:8081/nexus/repository/wuxian_snapshot/"
else
    echo "   Releases: http://nexus.dns.guazi.com:8081/nexus/repository/wuxian_release/"
fi
echo ""
echo "🔗 本地 Maven 仓库: ~/.m2/repository/cn/chesupai/android/logan/${VERSION}/"
echo ""
echo "📝 其他可用命令:"
echo "   发布到本地仓库: ./gradlew uploadLocal"
echo "   仅编译 AAR: ./gradlew :logan:assembleRelease"
echo "   清理项目: ./gradlew clean"
echo ""
