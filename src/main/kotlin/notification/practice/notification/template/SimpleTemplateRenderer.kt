package notification.practice.notification.template

import org.springframework.stereotype.Component

@Component
class SimpleTemplateRenderer : TemplateRenderer {
    override fun render(
        template: String,
        variables: Map<String, Any?>,
    ): String =
        variables.entries.fold(template) { acc, (key, value) ->
            acc.replace("{{$key}}", value?.toString() ?: "")
        }
}
