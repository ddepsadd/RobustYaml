package com.jetbrains.rider.plugins.robustyaml.quickfix

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PriorityAction

/**
 * A suggestion that carries its place in the list the plugin computed.
 *
 * Without it the ranking is thrown away by the platform, and the first thing offered is whichever
 * name happens to come first in the alphabet: on `layer: WallLaye` the tooltip proposed `AllMask`
 * while `WallLayer`, one edit away, waited under `More actions`. The path is
 * `DaemonTooltipActionProvider.extractMostPriorityFixFromHighlightInfo` →
 * `CachedIntentions.createAndUpdateActions` → `getAllActions`, and the sets there are ordered by
 * `IntentionActionWithTextCaching.compareTo`, whose bytecode reads: take `compareTo` of the action
 * when it is `Comparable`, otherwise `Comparing.compare(getText(), getText())`. So the rank has to
 * be visible to the platform, and there are two places that ask for it in two different ways —
 * `Comparable` for the sorted set behind the tooltip, `PriorityAction` for the Alt+Enter popup,
 * where `DefaultIntentionsOrderProvider.getPriorityWeight` is what does the sorting. Fixes that go
 * through an inspection reach the popup wrapped in `QuickFixWrapper`, which is final and not
 * `Comparable` but does implement `PriorityAction` — the second half is what survives there.
 *
 * Equal ranks must not compare equal: the sets are `TreeSet`s, so a tie silently drops a fix, and
 * two bad items in one sequence produce two rank-zero suggestions of the same family. The text
 * breaks it, which is also what the platform would have done on its own.
 */
interface RankedFix : IntentionAction, PriorityAction, Comparable<IntentionAction> {
    /** Position in the suggestion list, zero being the best. */
    val rank: Int

    override fun getPriority(): PriorityAction.Priority =
        if (rank == 0) PriorityAction.Priority.HIGH else PriorityAction.Priority.NORMAL

    override fun compareTo(other: IntentionAction): Int {
        if (other is RankedFix && familyName == other.familyName) {
            val byRank = rank.compareTo(other.rank)
            if (byRank != 0) return byRank
        }
        return text.compareTo(other.text)
    }
}
