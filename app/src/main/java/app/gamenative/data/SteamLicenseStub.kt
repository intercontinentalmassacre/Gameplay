package app.gamenative.data

import androidx.room.ColumnInfo
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import java.util.EnumSet

// Smallest possible stub for steam license comparisons
data class SteamLicenseStub(
    val packageId: Int,
    @ColumnInfo("last_change_number") val lastChangeNumber: Int,
    @ColumnInfo("access_token") val accessToken: Long,
    @ColumnInfo("owner_account_id") val ownerAccountId: List<Int>,
    @ColumnInfo("license_flags") val licenseFlags: EnumSet<ELicenseFlags>,
)
