package notification.practice.notification.template.service

interface TemplateRenderer {
    fun render(
        template: String,
        variables: Map<String, Any?>,
    ): String
}
