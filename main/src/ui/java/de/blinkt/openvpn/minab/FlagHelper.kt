/*
 * Minab Children — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab Children contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * FlagHelper (v0.9.7.1): maps 140+ countries (all known codes in CountryCodes)
 * to bundled tiny vector flags (~95 KB total), prioritising explicit emojis/names
 * and real IP-detected country codes over false-positive 2-letter tokens.
 */

package de.blinkt.openvpn.minab

import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object FlagHelper {
    private val NON_ALPHA_REGEX = Regex("[^a-z]+")
    private val profileFlagCache = ConcurrentHashMap<String, Int>()
    private const val MAX_CACHE_SIZE = 1000

    private val byCode: Map<String, Int> = mapOf(
        "ae" to R.drawable.flag_ae, "af" to R.drawable.flag_af, "al" to R.drawable.flag_al,
        "am" to R.drawable.flag_am, "ao" to R.drawable.flag_ao, "ar" to R.drawable.flag_ar,
        "at" to R.drawable.flag_at, "au" to R.drawable.flag_au, "az" to R.drawable.flag_az,
        "ba" to R.drawable.flag_ba, "bb" to R.drawable.flag_bb, "bd" to R.drawable.flag_bd,
        "be" to R.drawable.flag_be, "bg" to R.drawable.flag_bg, "bh" to R.drawable.flag_bh,
        "bn" to R.drawable.flag_bn, "bo" to R.drawable.flag_bo, "br" to R.drawable.flag_br,
        "bs" to R.drawable.flag_bs, "by" to R.drawable.flag_by, "ca" to R.drawable.flag_ca,
        "ch" to R.drawable.flag_ch, "ci" to R.drawable.flag_ci, "cl" to R.drawable.flag_cl,
        "cm" to R.drawable.flag_cm, "cn" to R.drawable.flag_cn, "co" to R.drawable.flag_co,
        "cr" to R.drawable.flag_cr, "cu" to R.drawable.flag_cu, "cy" to R.drawable.flag_cy,
        "cz" to R.drawable.flag_cz, "de" to R.drawable.flag_de, "dk" to R.drawable.flag_dk,
        "do" to R.drawable.flag_do, "dz" to R.drawable.flag_dz, "ec" to R.drawable.flag_ec,
        "ee" to R.drawable.flag_ee, "eg" to R.drawable.flag_eg, "es" to R.drawable.flag_es,
        "et" to R.drawable.flag_et, "fi" to R.drawable.flag_fi, "fk" to R.drawable.flag_fk,
        "fo" to R.drawable.flag_fo, "fr" to R.drawable.flag_fr, "gb" to R.drawable.flag_gb,
        "uk" to R.drawable.flag_gb, "ge" to R.drawable.flag_ge, "gh" to R.drawable.flag_gh,
        "gl" to R.drawable.flag_gl, "gr" to R.drawable.flag_gr, "gt" to R.drawable.flag_gt,
        "gy" to R.drawable.flag_gy, "hk" to R.drawable.flag_hk, "hn" to R.drawable.flag_hn,
        "hr" to R.drawable.flag_hr, "ht" to R.drawable.flag_ht, "hu" to R.drawable.flag_hu,
        "id" to R.drawable.flag_id, "ie" to R.drawable.flag_ie, "il" to R.drawable.flag_il,
        "in" to R.drawable.flag_in, "iq" to R.drawable.flag_iq, "ir" to R.drawable.flag_ir,
        "is" to R.drawable.flag_is, "it" to R.drawable.flag_it, "jm" to R.drawable.flag_jm,
        "jo" to R.drawable.flag_jo, "jp" to R.drawable.flag_jp, "ke" to R.drawable.flag_ke,
        "kg" to R.drawable.flag_kg, "kh" to R.drawable.flag_kh, "kr" to R.drawable.flag_kr,
        "kw" to R.drawable.flag_kw, "kz" to R.drawable.flag_kz, "la" to R.drawable.flag_la,
        "lb" to R.drawable.flag_lb, "lk" to R.drawable.flag_lk, "lt" to R.drawable.flag_lt,
        "lu" to R.drawable.flag_lu, "lv" to R.drawable.flag_lv, "ly" to R.drawable.flag_ly,
        "ma" to R.drawable.flag_ma, "mc" to R.drawable.flag_mc, "md" to R.drawable.flag_md,
        "me" to R.drawable.flag_me, "mg" to R.drawable.flag_mg, "mk" to R.drawable.flag_mk,
        "mm" to R.drawable.flag_mm, "mn" to R.drawable.flag_mn, "mt" to R.drawable.flag_mt,
        "mu" to R.drawable.flag_mu, "mv" to R.drawable.flag_mv, "mx" to R.drawable.flag_mx,
        "my" to R.drawable.flag_my, "mz" to R.drawable.flag_mz, "ng" to R.drawable.flag_ng,
        "ni" to R.drawable.flag_ni, "nl" to R.drawable.flag_nl, "no" to R.drawable.flag_no,
        "np" to R.drawable.flag_np, "nz" to R.drawable.flag_nz, "om" to R.drawable.flag_om,
        "pa" to R.drawable.flag_pa, "pe" to R.drawable.flag_pe, "ph" to R.drawable.flag_ph,
        "pk" to R.drawable.flag_pk, "pl" to R.drawable.flag_pl, "pr" to R.drawable.flag_pr,
        "pt" to R.drawable.flag_pt, "py" to R.drawable.flag_py, "qa" to R.drawable.flag_qa,
        "ro" to R.drawable.flag_ro, "rs" to R.drawable.flag_rs, "ru" to R.drawable.flag_ru,
        "sa" to R.drawable.flag_sa, "sc" to R.drawable.flag_sc, "sd" to R.drawable.flag_sd,
        "se" to R.drawable.flag_se, "sg" to R.drawable.flag_sg, "si" to R.drawable.flag_si,
        "sk" to R.drawable.flag_sk, "sn" to R.drawable.flag_sn, "sr" to R.drawable.flag_sr,
        "sv" to R.drawable.flag_sv, "th" to R.drawable.flag_th, "tj" to R.drawable.flag_tj,
        "tm" to R.drawable.flag_tm, "tn" to R.drawable.flag_tn, "tr" to R.drawable.flag_tr,
        "tt" to R.drawable.flag_tt, "tw" to R.drawable.flag_tw, "ua" to R.drawable.flag_ua,
        "us" to R.drawable.flag_us, "uy" to R.drawable.flag_uy, "uz" to R.drawable.flag_uz,
        "ve" to R.drawable.flag_ve, "vn" to R.drawable.flag_vn, "xk" to R.drawable.flag_xk,
        "ye" to R.drawable.flag_ye, "za" to R.drawable.flag_za, "zm" to R.drawable.flag_zm,
        "zw" to R.drawable.flag_zw
    )

    private val byName: Map<String, Int> by lazy {
        val m = HashMap<String, Int>(byCode.size * 2)
        for ((code, res) in byCode) {
            m[code] = res
            val countryName = CountryCodes.nameForCode(code)
            if (!countryName.isNullOrBlank()) {
                m[countryName.lowercase(Locale.US)] = res
            }
        }
        m["united states of america"] = R.drawable.flag_us
        m["usa"] = R.drawable.flag_us
        m["great britain"] = R.drawable.flag_gb
        m["england"] = R.drawable.flag_gb
        m["holland"] = R.drawable.flag_nl
        m["south korea"] = R.drawable.flag_kr
        m["korea"] = R.drawable.flag_kr
        m["turkiye"] = R.drawable.flag_tr
        m["united arab emirates"] = R.drawable.flag_ae
        m["czech republic"] = R.drawable.flag_cz
        m["czechia"] = R.drawable.flag_cz
        m["russian federation"] = R.drawable.flag_ru
        m
    }

    /** Drawable for a category name, or 0 when blank/uncategorized. */
    fun flagResForCategory(category: String?): Int {
        if (category.isNullOrBlank()) return 0
        val key = category.trim().lowercase(Locale.US)
        if (key == "uncategorized") return 0
        return byName[key] ?: R.drawable.minab_flag_globe
    }

    /** Drawable for a 2-letter ISO country code, or 0 if unknown. */
    fun flagResForCountryCode(code: String?): Int {
        if (code.isNullOrBlank()) return 0
        return byName[code.trim().lowercase(Locale.US)] ?: 0
    }

    fun flagResForProfile(profile: VpnProfile): Int =
        flagResForProfile(profile, null)

    fun flagResForProfile(profile: VpnProfile, context: android.content.Context?): Int {
        val name = profile.mName
        val host = try {
            profile.getPrimaryRemoteHost()
        } catch (_: Exception) {
            null
        }
        val detectedCode = if (context != null) {
            MinabServerCountry.get(context, profile.getUUIDString())
        } else null
        val category = profile.getCategory()
        val cacheKey = "${name ?: ""}\u0000${host ?: ""}\u0000${detectedCode ?: ""}\u0000${category ?: ""}"
        profileFlagCache[cacheKey]?.let { return it }

        val resolved = resolveFlag(name, host, detectedCode, category)
        if (profileFlagCache.size > MAX_CACHE_SIZE) profileFlagCache.clear()
        profileFlagCache[cacheKey] = resolved
        return resolved
    }

    private fun resolveFlag(
        name: String?,
        host: String?,
        detectedCode: String?,
        category: String?
    ): Int {
        // 1. Real IP-detected country code is most reliable when present
        if (!detectedCode.isNullOrBlank()) {
            byName[detectedCode.trim().lowercase(Locale.US)]?.let { return it }
        }
        // 2. Explicit emoji flag or full country name in profile name/host
        flagEmojiCode(name)?.let { byName[it]?.let { res -> return res } }
        flagEmojiCode(host)?.let { byName[it]?.let { res -> return res } }
        scanLongCountryName(name)?.let { return it }
        scanLongCountryName(host)?.let { return it }
        // 3. Assigned country category (when it is a known country)
        if (!category.isNullOrBlank()) {
            val key = category.trim().lowercase(Locale.US)
            if (key != "uncategorized") {
                byName[key]?.let { return it }
            }
        }
        // 4. Conservative 2-letter code scan (only well-known unambiguous server codes if no IP/category)
        scanCodeToken(name)?.let { return it }
        scanCodeToken(host)?.let { return it }
        return R.drawable.minab_flag_globe
    }

    private val longCountryNames: List<Pair<String, Int>> by lazy {
        byName.entries.filter { it.key.length > 3 }.sortedByDescending { it.key.length }
            .map { it.key to it.value }
    }

    private val SAFE_PREFIX_CODES = setOf(
        "us", "gb", "uk", "de", "nl", "fr", "ca", "jp", "kr", "sg", "au",
        "ch", "se", "fi", "dk", "pl", "cz", "ru", "ua", "tr", "ro", "hu",
        "bg", "br", "hk", "tw", "ae", "ir", "gr", "es", "pt", "ie"
    )

    private fun flagEmojiCode(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val j = i + Character.charCount(cp)
                if (j < text.length) {
                    val cp2 = Character.codePointAt(text, j)
                    if (cp2 in 0x1F1E6..0x1F1FF) {
                        val a = 'a' + (cp - 0x1F1E6)
                        val b = 'a' + (cp2 - 0x1F1E6)
                        return "$a$b"
                    }
                }
            }
            i += Character.charCount(cp)
        }
        return null
    }

    private fun scanLongCountryName(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase(Locale.US)
        for ((name, res) in longCountryNames) {
            if (lower.contains(name)) return res
        }
        return null
    }

    private fun scanCodeToken(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val lower = text.lowercase(Locale.US)
        for (token in lower.split(NON_ALPHA_REGEX)) {
            if (token.length == 2 && SAFE_PREFIX_CODES.contains(token)) {
                byCode[token]?.let { return it }
            }
        }
        return null
    }
}
