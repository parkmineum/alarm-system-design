package notification.practice.notification.template

interface TemplateRenderer {
    fun render(
        template: String,
        variables: Map<String, Any?>,
    ): String
}
