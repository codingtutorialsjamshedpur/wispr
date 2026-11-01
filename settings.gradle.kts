// Wispr by Deepu Gupta. Copyright 2026 Deepu Gupta. SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WisprByDeepuGupta"
include(":app")
