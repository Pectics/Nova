import org.gradle.api.Plugin
import org.gradle.api.Project

class BundlerPlugin : Plugin<Project> {
    
    override fun apply(target: Project) {
        val novaLoaderApiCfg = target.configurations.create("novaLoaderApi")
        target.configurations.getByName("api").extendsFrom(novaLoaderApiCfg)

        val novaLoaderCfg = target.configurations.create("novaLoader").apply { extendsFrom(novaLoaderApiCfg) }
        target.configurations.getByName("implementation").extendsFrom(novaLoaderCfg)

        val paperLibraryCfg = target.configurations.create("paperLibrary").apply {
            description = "Dependencies that will be provided through paper-plugin.yml libraries."
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        target.configurations.getByName("compileOnly").extendsFrom(paperLibraryCfg)
    }

}