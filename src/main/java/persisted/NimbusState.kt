package persisted


data class NimbusState(
        val projectName: String = "",
        val compilationTimeStamp: String  = "",
        val afterDeployments: Map<String, List<String>> = mutableMapOf()
)