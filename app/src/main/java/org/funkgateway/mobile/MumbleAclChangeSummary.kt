package org.funkgateway.mobile

/**
 * Beschreibt ausschließlich lokale ACL-Änderungen.
 * Keine Netzwerkkommunikation und keine Schreiboperation.
 */
object MumbleAclChangeSummary {

    data class Summary(
        val addedGroups: List<String>,
        val removedGroups: List<String>,
        val modifiedGroups: List<String>,
        val addedRules: Int,
        val removedRules: Int,
        val modifiedRules: Int,
        val rulesReordered: Boolean,
        val inheritanceChanged: Boolean
    ) {
        fun hasChanges(): Boolean =
            addedGroups.isNotEmpty() ||
                removedGroups.isNotEmpty() ||
                modifiedGroups.isNotEmpty() ||
                addedRules > 0 ||
                removedRules > 0 ||
                modifiedRules > 0 ||
                rulesReordered ||
                inheritanceChanged
    }

    /**
     * Prüft, ob eine gemeinsame Teilfolge alle weiterhin
     * vorhandenen Regelvorkommen abdecken kann.
     * Berücksichtigt auch mehrfach identische Regeln.
     */
    private fun <T> retainedOrderChanged(
        original: List<T>,
        current: List<T>
    ): Boolean {
        val originalCounts = original.groupingBy { it }.eachCount()
        val currentCounts = current.groupingBy { it }.eachCount()

        val retained = originalCounts.keys.sumOf { rule ->
            minOf(originalCounts[rule] ?: 0, currentCounts[rule] ?: 0)
        }

        val dp = IntArray(current.size + 1)
        for (item in original) {
            var previous = 0
            for (j in 1..current.size) {
                val saved = dp[j]
                dp[j] = if (item == current[j - 1]) {
                    previous + 1
                } else {
                    maxOf(dp[j], dp[j - 1])
                }
                previous = saved
            }
        }

        return dp[current.size] < retained
    }

    fun summarize(draft: MumbleAclDraft): Summary {
        val originalGroups = draft.original.groups
            .filterNot { it.inherited }
            .associateBy { it.name }

        val currentGroups = MumbleAclPreparation.localGroups(draft)
            .associateBy { it.name }

        val addedGroups =
            (currentGroups.keys - originalGroups.keys).sorted()

        val removedGroups =
            (originalGroups.keys - currentGroups.keys).sorted()

        val modifiedGroups =
            (originalGroups.keys intersect currentGroups.keys)
                .filter { name ->
                    originalGroups[name]
                        ?.toBuilder()
                        ?.clearInheritedMembers()
                        ?.build() != currentGroups[name]
                }
                .sorted()

        val originalRules = draft.original.rules
            .filterNot { it.inherited }

        val currentRules = MumbleAclPreparation.localRules(draft)

        // Gleicher Regelbestand bei anderer Reihenfolge:
        // keine inhaltliche Änderung behaupten.
        val sameRuleInventory =
            originalRules.groupingBy { it }.eachCount() ==
                currentRules.groupingBy { it }.eachCount()

        val onlyReordered =
            originalRules != currentRules && sameRuleInventory

        val rulesReordered =
            onlyReordered ||
                retainedOrderChanged(originalRules, currentRules)

        // Multimengenvergleich: identische Regeln werden auch bei
        // Verschiebungen und mehrfachen Vorkommen korrekt gezählt.
        val originalCounts = originalRules.groupingBy { it }.eachCount()
        val currentCounts = currentRules.groupingBy { it }.eachCount()

        val unmatchedOriginal = originalCounts.entries.sumOf { (rule, count) ->
            (count - (currentCounts[rule] ?: 0)).coerceAtLeast(0)
        }
        val unmatchedCurrent = currentCounts.entries.sumOf { (rule, count) ->
            (count - (originalCounts[rule] ?: 0)).coerceAtLeast(0)
        }

        // Eine Bearbeitung lässt sich ohne stabile Regel-ID nicht
        // eindeutig von Entfernen plus Hinzufügen unterscheiden.
        val modifiedRules = 0

        return Summary(
            addedGroups = addedGroups,
            removedGroups = removedGroups,
            modifiedGroups = modifiedGroups,
            addedRules = unmatchedCurrent,
            removedRules = unmatchedOriginal,
            modifiedRules = modifiedRules,
            rulesReordered = rulesReordered,
            inheritanceChanged =
                draft.inheritAcls != draft.original.inheritAcls
        )
    }
}
