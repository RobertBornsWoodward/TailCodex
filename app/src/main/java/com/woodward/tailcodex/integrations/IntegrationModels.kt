package com.woodward.tailcodex.integrations

enum class IntegrationRiskLevel { READ, MUTATE_WORKSPACE, PROCESS_CONTROL, FULL_TERMINAL, PRIVILEGED }

data class IntegrationDescriptor(
    val id: String,
    val version: String,
    val riskLevel: IntegrationRiskLevel,
    val mutability: String,
    val timeoutClass: String,
    val inputSchema: String,
    val outputSchema: String,
    val presentationHint: String,
    val requiredCapabilities: Set<String>,
)

/** Android-side boundary for I5 typed integrations; no dynamic plugin loading is permitted. */
interface IntegrationCatalog {
    fun descriptors(): List<IntegrationDescriptor>
}

object EmptyIntegrationCatalog : IntegrationCatalog {
    override fun descriptors(): List<IntegrationDescriptor> = emptyList()
}
