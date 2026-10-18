# Halo 插件的本地校验、构建与格式化入口。
#
# 构建接口约定：
#   - make verify：质量门禁（编译、单元测试、类型检查、静态检查），任一失败以非零退出。
#   - make build PROJECT=<名> VERSION=<版本> OUTPUT=<完整路径>：生成 OUTPUT 指定的单个 JAR。
#
# 本地默认构建示例：make build OUTPUT=dist/plugin-ai-cover.jar

# 插件项目名与 settings.gradle 的 rootProject.name 保持一致。
PROJECT ?= plugin-ai-cover
VERSION ?= 1.0.0-SNAPSHOT
GRADLE ?= ./gradlew
UI_DIR ?= ui

# 版本参数支持带 v 前缀的标签，Gradle 版本号与产物名使用去掉前缀的版本。
VERSION_CLEAN = $(patsubst v%,%,$(VERSION))
JAR_PATH = build/libs/$(PROJECT)-$(VERSION_CLEAN).jar

.PHONY: verify build format

# 质量门禁：后端编译、后端与前端单元测试，以及前端类型检查和静态检查。
# 不调用带 --fix 的脚本，保证流水线只做校验、不修改工作区。
verify:
	$(GRADLE) check
	cd $(UI_DIR) && pnpm run type-check
	cd $(UI_DIR) && pnpm exec oxlint .
	cd $(UI_DIR) && pnpm exec eslint .

# 生成包含前端资源的插件 JAR，并复制到 OUTPUT 指定位置。
build:
	@test -n "$(OUTPUT)" || { printf 'OUTPUT is required\n' >&2; exit 1; }
	$(GRADLE) jar -Pversion=$(VERSION_CLEAN)
	mkdir -p "$$(dirname "$(OUTPUT)")"
	cp "$(JAR_PATH)" "$(OUTPUT)"

# 格式化前端代码。
format:
	cd $(UI_DIR) && pnpm run format
