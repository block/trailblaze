package xyz.block.trailblaze.report

import freemarker.template.Configuration
import freemarker.template.Template
import freemarker.template.TemplateExceptionHandler
import xyz.block.trailblaze.report.utils.TemplateHelpers
import java.io.StringWriter

object ReportRenderer {
  fun renderTemplateFromResources(templatePath: String, dataModel: Map<String, Any>): String {
    val cfg = Configuration(Configuration.VERSION_2_3_34).apply {
      defaultEncoding = "UTF-8"
      templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
      logTemplateExceptions = false
      wrapUncheckedExceptions = true

      // Load templates from src/main/resources
      setClassLoaderForTemplateLoading(Thread.currentThread().contextClassLoader, "/templates")
    }
    cfg.setSharedVariable(TemplateHelpers::class.simpleName, TemplateHelpers)

    val template: Template = cfg.getTemplate(templatePath)
    val out = StringWriter()
    template.process(dataModel, out)
    return out.toString()
  }
}
