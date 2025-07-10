package org.jetbrains.mcpserverplugin.general

import com.intellij.analysis.AnalysisScope
import com.intellij.configurationStore.JbXmlOutputter
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.packageDependencies.ForwardDependenciesBuilder
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import org.jdom.Element
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.io.File
import kotlin.io.path.exists

@Serializable
data class FileArgs(val pathInProject: String)

/**
 * File dependency analysis tool implementing IntelliJ IDEA's core dependency analysis functionality.
 * 
 * This implementation reuses the exact same core logic as IDEA's "Analyze -> Dependencies..." feature:
 * 1. AnalysisScope - Creates the analysis scope
 * 2. ForwardDependenciesBuilder - Performs dependency analysis
 * 3. JbXmlOutputter - Generates XML export format consistent with IDEA
 * 
 * Provides the same functionality as the manual IDE operation:
 * Right-click file -> Analyze -> Dependencies... -> Analyze -> Export to Text File
 */
class GetFileDependenciesTool : AbstractMcpTool<FileArgs>(FileArgs.serializer()) {
    override val name: String = "get_file_dependencies"
    override val description: String = """
        Retrieves dependencies of a specified file in the project.
        
        This tool implements the exact same functionality as IntelliJ IDEA's:
        Right-click file -> Analyze -> Dependencies... -> Analyze -> Export to Text File
        
        Uses IntelliJ IDEA's core dependency analysis APIs:
        - AnalysisScope: Defines the scope of analysis
        - ForwardDependenciesBuilder: Builds forward dependency relationships  
        - JbXmlOutputter: Exports in IDEA's native XML format
        
        Returns JSON with:
        - target_file: The analyzed file path
        - dependencies_count: Total number of dependencies found
        - files_analyzed: Number of files in the analysis scope
        - output_file: Path where full XML was saved (/tmp/deps.log)
        - dependencies_summary: Human-readable summary
        - dependencies_xml: Complete dependency tree in XML format
        
        Takes a file path relative to project root.
        Supports all file types that IntelliJ IDEA can analyze.
    """.trimIndent()

    override fun handle(project: Project, args: FileArgs): Response {
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project directory not found")

        return runReadAction {
            try {
                // Resolve target file path
                val targetPath = projectDir.resolveRel(args.pathInProject)
                if (!targetPath.exists()) {
                    return@runReadAction Response(error = "file not found: ${args.pathInProject}")
                }

                // Get virtual file
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(targetPath)
                    ?: return@runReadAction Response(error = "virtual file not found")

                // Get PSI file
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    ?: return@runReadAction Response(error = "PSI file not found")

                // === Core dependency analysis logic (based on IDEA source code) ===
                
                // Step 1: Create analysis scope - analyze only the target file
                // Corresponds to AnalyzeDependenciesHandler constructor in IDEA source
                val analysisScope = AnalysisScope(psiFile)
                
                // Step 2: Create forward dependencies builder
                // Corresponds to AnalyzeDependenciesHandler.createDependenciesBuilder in IDEA source
                // transitiveBorder = 0 means no limit on transitive dependency depth
                val dependenciesBuilder = ForwardDependenciesBuilder(project, analysisScope, 0)
                
                // Step 3: Execute dependency analysis
                // Corresponds to DependenciesHandlerBase.perform method in IDEA source
                dependenciesBuilder.analyze()
                
                // Step 4: Get dependency mapping
                // Corresponds to DependenciesBuilder.getDependencies in IDEA source
                val dependencies = dependenciesBuilder.dependencies
                
                // Step 5: Generate XML export content
                // Corresponds to DependenciesPanel.DependenciesExporterToTextFile.getReportText in IDEA source
                val xmlContent = generateDependenciesXml(dependencies, project)
                
                // Step 6: Save to file (simulates IDEA's Export to Text File functionality)
                val outputFile = "/tmp/deps.log"
                saveToFile(xmlContent, outputFile)
                
                // Step 7: Generate human-readable summary
                val summary = generateSummary(dependencies, args.pathInProject)
                
                // Build JSON response
                val response = createJsonResponse(
                    targetFile = args.pathInProject,
                    dependenciesCount = dependencies.values.sumOf { it.size },
                    filesAnalyzed = dependencies.keys.size,
                    outputFile = outputFile,
                    summary = summary,
                    xmlContent = xmlContent
                )
                
                Response(response)

            } catch (e: Exception) {
                Response(error = "Dependency analysis failed: ${e.message}")
            }
        }
    }
    
    /**
     * Generates XML export content for dependency relationships.
     * 
     * Based completely on IDEA's source code implementation:
     * DependenciesPanel.DependenciesExporterToTextFile.getReportText()
     */
    private fun generateDependenciesXml(
        dependencies: Map<PsiFile, Set<PsiFile>>, 
        project: Project
    ): String {
        val rootElement = Element("root")
        // isBackward="false" indicates this is forward dependency analysis
        rootElement.setAttribute("isBackward", "false")
        
        // Sort files by path to match IDEA source code behavior
        val sortedFiles = dependencies.keys.sortedWith { f1, f2 ->
            val path1 = f1.virtualFile?.path ?: ""
            val path2 = f2.virtualFile?.path ?: ""
            path1.compareTo(path2, ignoreCase = true)
        }
        
        // Create XML elements for each file
        for (file in sortedFiles) {
            val fileElement = Element("file")
            fileElement.setAttribute("path", file.virtualFile?.path ?: "unknown")
            
            // Add all dependencies for this file
            val fileDependencies = dependencies[file] ?: emptySet()
            for (dependency in fileDependencies) {
                val depElement = Element("dependency")
                depElement.setAttribute("path", dependency.virtualFile?.path ?: "unknown")
                fileElement.addContent(depElement)
            }
            
            rootElement.addContent(fileElement)
        }
        
        return try {
            // Use IDEA's XML outputter to maintain exact compatibility with native export format
            JbXmlOutputter.collapseMacrosAndWrite(rootElement, project)
        } catch (e: Exception) {
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<error>Failed to generate XML: ${e.message}</error>"
        }
    }
    
    /**
     * Generates a human-readable summary of dependency analysis results.
     */
    private fun generateSummary(
        dependencies: Map<PsiFile, Set<PsiFile>>, 
        targetFile: String
    ): String {
        val sb = StringBuilder()
        sb.append("Dependencies Analysis for: $targetFile\\n\\n")
        
        val totalFiles = dependencies.keys.size
        val totalDeps = dependencies.values.sumOf { it.size }
        
        sb.append("Analysis Results:\\n")
        sb.append("- Files analyzed: $totalFiles\\n")
        sb.append("- Total dependencies: $totalDeps\\n\\n")
        
        if (dependencies.isNotEmpty()) {
            sb.append("Dependency breakdown:\\n")
            dependencies.forEach { (file, deps) ->
                val fileName = file.name
                val depCount = deps.size
                sb.append("- $fileName: $depCount dependencies\\n")
                
                // Show first few dependencies as preview
                deps.take(3).forEach { dep ->
                    sb.append("  └─ ${dep.name}\\n")
                }
                if (deps.size > 3) {
                    sb.append("  └─ ... and ${deps.size - 3} more\\n")
                }
            }
        } else {
            sb.append("No dependencies found. File may be self-contained.\\n")
        }
        
        return sb.toString()
    }
    
    /**
     * Saves content to a file, creating parent directories if necessary.
     */
    private fun saveToFile(content: String, filePath: String) {
        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            // Silently handle file save errors to not affect core functionality
        }
    }
    
    /**
     * Creates a structured JSON response with dependency analysis results.
     */
    private fun createJsonResponse(
        targetFile: String,
        dependenciesCount: Int,
        filesAnalyzed: Int,
        outputFile: String,
        summary: String,
        xmlContent: String
    ): String {
        // Escape JSON string content
        fun String.escapeJson(): String = this
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        
        return buildString {
            append("{\n")
            append("  \"target_file\": \"${targetFile.escapeJson()}\",\n")
            append("  \"dependencies_count\": $dependenciesCount,\n")
            append("  \"files_analyzed\": $filesAnalyzed,\n")
            append("  \"output_file\": \"${outputFile.escapeJson()}\",\n")
            append("  \"dependencies_summary\": \"${summary.escapeJson()}\",\n")
            append("  \"dependencies_xml\": \"${xmlContent.escapeJson()}\"\n")
            append("}")
        }
    }
}