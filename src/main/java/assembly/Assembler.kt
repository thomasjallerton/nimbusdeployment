package assembly

import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.eclipse.aether.RepositorySystemSession
import persisted.HandlerInformation


class Assembler(
        private val mavenProject: MavenProject,
        repoSession: RepositorySystemSession,
        private val log: Log
) {

    private val mavenDependencies: Map<String, String>

    private val extraDependencies: Set<String>


    init {
        val mavenRepositoryAnalyser = MavenRepositoryAnalyser(repoSession, mavenProject)
        val (pMavenDependencies, pExtraDependencies) = mavenRepositoryAnalyser.analyseRepository()
        mavenDependencies = pMavenDependencies
        extraDependencies = pExtraDependencies
    }

    fun assembleProject(handlers: List<HandlerInformation>) {

        log.info("Processing Dependencies")

        val dependencyProcessor = DependencyProcessor(mavenDependencies, extraDependencies)

        val (outputs, jarDependencies) = dependencyProcessor.determineDependencies(handlers)

        log.info("Creating Jars")
        val jarsCreator = JarsCreator(log, mavenProject)

        jarsCreator.createJars(jarDependencies, outputs)
    }



}