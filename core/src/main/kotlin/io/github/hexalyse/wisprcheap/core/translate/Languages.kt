package io.github.hexalyse.wisprcheap.core.translate

/**
 * Language codes and English names, ported from the desktop `lang.rs` (ISO 639-1, 639-2/T, 639-2/B, name).
 * Used instead of java.util.Locale, whose codes and names differ between the JVM and Android (e.g. "iw").
 */
object Languages {
    data class Language(val code: String, val name: String)

    private const val TABLE = """
aa|aar||Afar
ab|abk||Abkhazian
ae|ave||Avestan
af|afr||Afrikaans
ak|aka||Akan
am|amh||Amharic
an|arg||Aragonese
ar|ara||Arabic
as|asm||Assamese
av|ava||Avaric
ay|aym||Aymara
az|aze||Azerbaijani
ba|bak||Bashkir
be|bel||Belarusian
bg|bul||Bulgarian
bi|bis||Bislama
bm|bam||Bambara
bn|ben||Bangla
bo|bod|tib|Tibetan
br|bre||Breton
bs|bos||Bosnian
ca|cat||Catalan
ce|che||Chechen
ch|cha||Chamorro
co|cos||Corsican
cr|cre||Cree
cs|ces|cze|Czech
cu|chu||Church Slavic
cv|chv||Chuvash
cy|cym|wel|Welsh
da|dan||Danish
de|deu|ger|German
dv|div||Divehi
dz|dzo||Dzongkha
ee|ewe||Ewe
el|ell|gre|Greek
en|eng||English
eo|epo||Esperanto
es|spa||Spanish
et|est||Estonian
eu|eus|baq|Basque
fa|fas|per|Persian
ff|ful||Fula
fi|fin||Finnish
fj|fij||Fijian
fo|fao||Faroese
fr|fra|fre|French
fy|fry||Western Frisian
ga|gle||Irish
gd|gla||Scottish Gaelic
gl|glg||Galician
gn|grn||Guarani
gu|guj||Gujarati
gv|glv||Manx
ha|hau||Hausa
he|heb||Hebrew
hi|hin||Hindi
ho|hmo||Hiri Motu
hr|hrv||Croatian
ht|hat||Haitian Creole
hu|hun||Hungarian
hy|hye|arm|Armenian
hz|her||Herero
ia|ina||Interlingua
id|ind||Indonesian
ie|ile||Interlingue
ig|ibo||Igbo
ii|iii||Sichuan Yi
ik|ipk||Inupiaq
io|ido||Ido
is|isl|ice|Icelandic
it|ita||Italian
iu|iku||Inuktitut
ja|jpn||Japanese
jv|jav||Javanese
ka|kat|geo|Georgian
kg|kon||Kongo
ki|kik||Kikuyu
kj|kua||Kuanyama
kk|kaz||Kazakh
kl|kal||Kalaallisut
km|khm||Khmer
kn|kan||Kannada
ko|kor||Korean
kr|kau||Kanuri
ks|kas||Kashmiri
ku|kur||Kurdish
kv|kom||Komi
kw|cor||Cornish
ky|kir||Kyrgyz
la|lat||Latin
lb|ltz||Luxembourgish
lg|lug||Ganda
li|lim||Limburgish
ln|lin||Lingala
lo|lao||Lao
lt|lit||Lithuanian
lu|lub||Luba-Katanga
lv|lav||Latvian
mg|mlg||Malagasy
mh|mah||Marshallese
mi|mri|mao|Māori
mk|mkd|mac|Macedonian
ml|mal||Malayalam
mn|mon||Mongolian
mr|mar||Marathi
ms|msa|may|Malay
mt|mlt||Maltese
my|mya|bur|Burmese
na|nau||Nauru
nb|nob||Norwegian Bokmål
nd|nde||North Ndebele
ne|nep||Nepali
ng|ndo||Ndonga
nl|nld|dut|Dutch
nn|nno||Norwegian Nynorsk
no|nor||Norwegian
nr|nbl||South Ndebele
nv|nav||Navajo
ny|nya||Nyanja
oc|oci||Occitan
oj|oji||Ojibwa
om|orm||Oromo
or|ori||Odia
os|oss||Ossetic
pa|pan||Punjabi
pi|pli||Pali
pl|pol||Polish
ps|pus||Pashto
pt|por||Portuguese
qu|que||Quechua
rm|roh||Romansh
rn|run||Rundi
ro|ron|rum|Romanian
ru|rus||Russian
rw|kin||Kinyarwanda
sa|san||Sanskrit
sc|srd||Sardinian
sd|snd||Sindhi
se|sme||Northern Sami
sg|sag||Sango
si|sin||Sinhala
sk|slk|slo|Slovak
sl|slv||Slovenian
sm|smo||Samoan
sn|sna||Shona
so|som||Somali
sq|sqi|alb|Albanian
sr|srp||Serbian
ss|ssw||Swati
st|sot||Southern Sotho
su|sun||Sundanese
sv|swe||Swedish
sw|swa||Swahili
ta|tam||Tamil
te|tel||Telugu
tg|tgk||Tajik
th|tha||Thai
ti|tir||Tigrinya
tk|tuk||Turkmen
tl|tgl||Tagalog
tn|tsn||Tswana
to|ton||Tongan
tr|tur||Turkish
ts|tso||Tsonga
tt|tat||Tatar
tw|twi||Twi
ty|tah||Tahitian
ug|uig||Uyghur
uk|ukr||Ukrainian
ur|urd||Urdu
uz|uzb||Uzbek
ve|ven||Venda
vi|vie||Vietnamese
vo|vol||Volapük
wa|wln||Walloon
wo|wol||Wolof
xh|xho||Xhosa
yi|yid||Yiddish
yo|yor||Yoruba
za|zha||Zhuang
zh|zho|chi|Chinese
zu|zul||Zulu
"""

    private class Row(val two: String, val t: String, val b: String, val name: String)

    private val rows: List<Row> = TABLE.trim().lines().map { line ->
        val p = line.split('|')
        Row(p[0], p[1], p[2], p[3])
    }

    /** Languages without a two-letter code. */
    private val extraNames = mapOf(
        "ast" to "Asturian", "ceb" to "Cebuano", "fil" to "Filipino", "gsw" to "Swiss German",
        "haw" to "Hawaiian", "hmn" to "Hmong", "nds" to "Low German", "sco" to "Scots", "yue" to "Cantonese",
    )

    /** Deprecated codes that canonicalize to another one. */
    private val aliases = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "jw" to "jv", "mo" to "ro", "sh" to "sr")

    /** All languages, sorted by English name (for pickers). */
    val all: List<Language> by lazy {
        (rows.map { Language(it.two, it.name) } + extraNames.map { (c, n) -> Language(c, n) }).sortedBy { it.name }
    }

    /**
     * Canonical primary language subtag of a BCP 47 tag ("FR" -> "fr", "fra" -> "fr", "en-US" -> "en"),
     * or null when the tag isn't well-formed.
     */
    fun canonical(code: String): String? {
        val c = code.trim()
        if (c.isEmpty()) return null
        val subtags = c.split('-')
        val primary = subtags[0]
        if (!primary.all { it in 'a'..'z' || it in 'A'..'Z' } || primary.length !in setOf(2, 3, 5, 6, 7, 8)) return null
        if (subtags.drop(1).any { s -> s.isEmpty() || s.length > 8 || !s.all { it.isLetterOrDigit() && it.code < 128 } }) {
            return null
        }
        val lower = primary.lowercase()
        aliases[lower]?.let { return it }
        if (lower.length == 3) rows.firstOrNull { it.t == lower || (it.b.isNotEmpty() && it.b == lower) }?.let { return it.two }
        return lower
    }

    /** English name of a canonical code ("fr" -> "French"), or the code itself when unknown. */
    fun name(code: String): String = rows.firstOrNull { it.two == code }?.name ?: extraNames[code] ?: code
}
