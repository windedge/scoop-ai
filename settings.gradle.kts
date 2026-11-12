rootProject.name = "scoop-ai"
include("scoop-ai-core")
project(":scoop-ai-core").projectDir = file("core")
include("scoop-ai-tools")
project(":scoop-ai-tools").projectDir = file("tools")
include("cli")
