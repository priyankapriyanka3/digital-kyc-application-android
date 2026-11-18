package com.sapphirebank.kyc.data

import com.sapphirebank.kyc.domain.repository.CreditBureau

/**
 * SIMULATED credit bureau: there is no bureau integration in this build. Known sample PANs return fixed scores so the
 * demo is deterministic; any other PAN gets a stable pseudo-score derived from the PAN. Replace with a real client.
 */
class SimulatedBureau(
    private val fixed: Map<String, Int> = SeedData.bureauScores,
) : CreditBureau {
    override fun score(pan: String): Int? {
        val p = pan.trim().uppercase()
        if (p.isEmpty()) return null
        return fixed[p] ?: (640 + Math.floorMod(p.hashCode(), 200))
    }
}
