package app.rcq.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rcq.android.R
import app.rcq.android.Session
import app.rcq.android.net.RcqFederation
import app.rcq.android.data.LocalStores
import app.rcq.android.model.UserStatus
import app.rcq.android.net.CrossIslandSender
import app.rcq.android.net.CrossIslandStore
import app.rcq.android.net.RcqApi
import kotlinx.coroutines.launch

private val DANGER = Color(0xFFE5484D)
// Amber, not red: a safety-number change asks you to check something, it
// does not forbid anything. Red read as "blocked" to the reporter (#407).
private val WARNING = Color(0xFFF5A524)

/** Profile card for a 1:1 contact — the peer analogue of [GroupInfoScreen],
 *  opened by tapping the chat header. Shows presence/last-seen, status
 *  message, and any visibility-gated profile fields the server returns,
 *  plus per-contact actions (favorite, mute, block, remove). */
@Composable
internal fun ContactInfoScreen(session: Session, uin: Int, onBack: () -> Unit, onRemoved: () -> Unit, onOpenChat: (Int) -> Unit = {}, groupHost: String? = null, guestMember: Boolean = false) {
    val c = RcqTheme.colors
    // Signed in to a guest copy (spec 2026-09-15, 12.1): the card still shows
    // who this is, and offers no 1:1 of any kind from the copy.
    val guestCopy by session.primaryIsGuest.collectAsState()
    // [guestMember]: opened for a guest copy in a room (decision D5). Its name,
    // what it is, and one Add: the ordinary contact request to its number on
    // the room's island. No Message, no visit ping, no other action.
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val contacts by session.contacts.collectAsState()
    // ⚠ A UIN alone does not name a person: #134 is a different account on
    // every island. When the caller knows WHICH island it means, resolve on
    // that island, otherwise a same-numbered contact from elsewhere answers
    // instead — that is report #433 (the row said is2, the profile was api)
    // and report #429 (the request went to the api account, and the one on
    // is2 sat pending forever). [groupHost] naming OUR OWN island is the
    // caller saying "the local one", which is why it can no longer fall back.
    val here = groupHost != null && groupHost.equals(session.currentServer, true)
    val contact = when {
        here -> contacts.firstOrNull { it.uin == uin && it.host == null }
        groupHost != null -> contacts.firstOrNull { it.uin == uin && groupHost.equals(it.host, true) }
            ?: contacts.firstOrNull { it.uin == uin && it.host == null }
        else -> contacts.firstOrNull { it.uin == uin }
    }
    // ⚠ The roster is not the only thing that knows which island a 1:1 is on,
    // and taking it as such is the other half of #1024: a cross-island contact
    // lives in CrossIslandStore, and the visible roster only carries a copy of
    // it. Until that copy is folded in (an accept that came from another
    // device, a roster answering 304) the row is absent here, the card resolved
    // on our own island, and it drew whoever holds that number THERE. Read the
    // store as the last word on the host, not the first: see [PeerIsland].
    // remember(uin), because this is a prefs read and a Gson parse, and a card
    // recomposes for every presence frame.
    val storeHost = remember(uin) { CrossIslandStore.findByUin(uin)?.host }

    val thread = LocalStores.peerThread(uin)
    val favorites by LocalStores.favorites.collectAsState()
    val muted by LocalStores.muted.collectAsState()
    val isFav = thread in favorites
    val isMuted = thread in muted

    var profile by remember { mutableStateOf<RcqApi.MeProfile?>(null) }
    var requestSent by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var safetyNumber by remember { mutableStateOf<String?>(null) }
    var safetyLoading by remember { mutableStateOf(false) }
    var identityChanged by remember { mutableStateOf(false) }
    // Open card fetched from a cross-island GROUP member's island (name/status),
    // since they aren't a contact and aren't on our island.
    var ciCardName by remember { mutableStateOf<String?>(null) }
    var ciCardStatus by remember { mutableStateOf<String?>(null) }
    // Report #938: the card's photo opens full screen on a tap. Reset with the
    // UIN so walking from one card to another cannot leave the viewer standing
    // over the wrong person.
    var showAvatar by remember(uin) { mutableStateOf(false) }

    // §5c: a cross-island peer's profile lives on ITS island — our own
    // /users/{uin}/info 404s. crossIslandHost is the existing contact's host, OR
    // (when opened from a cross-island GROUP member who isn't a contact yet) the
    // group's host. In both cases we render from the open card, never our island.
    val crossIslandHost = app.rcq.android.data.PeerIsland.cardHost(
        callerHost = groupHost,
        ourIsland = session.currentServer,
        rosterMatched = contact != null,
        rosterHost = contact?.host,
        storeHost = storeHost,
    )
    // ⚠⚠ NO LONGER GATED ON THE ISLAND. This comment used to say the blob
    // lives on THEIR island and ours has never held it. §5e changed that from
    // both ends: a cross-island contact DEPOSITS the encrypted blob to our
    // island under the same id and hands us the key in a sealed envelope, so
    // it resolves from our own media endpoint like any other picture — and
    // keeps resolving while their island is down, which is the whole reason it
    // is deposited rather than pulled. The gate was simply left behind, and it
    // showed a flower for people whose face we were holding. Presence stays
    // cross-island below, because presence really does not cross.
    val avatarMediaId = contact?.avatarMediaId
    val avatarMediaKey = contact?.avatarMediaKey
    // ⚠ "The island answered 404" is a different fact from "the island did not
    // answer", and this screen used to lose the difference: loadPeerProfile
    // returned null for both, the card fell back to "$uin", and a number nobody
    // holds was drawn as a person with an avatar, a presence dot and a button
    // offering to send them a contact request (#483).
    var notFound by remember(uin) { mutableStateOf(false) }
    LaunchedEffect(uin) {
        if (crossIslandHost == null) {
            val (p, absent) = session.loadPeerProfileDetailed(uin)
            profile = p
            notFound = absent
            if (!absent) {
                identityChanged = session.peerIdentityChanged(uin)
                if (!guestCopy && !guestMember) runCatching { session.sendVisit(uin) }
            }
        } else if (contact == null) {
            // Somebody on another island with no row in the visible roster:
            // fetch their open card from THEIR island instead of our own (which
            // would 404, the founder's report). Two ways to get here now. A
            // cross-island group member, who is not a contact at all, is the
            // original one. The other is #1024: a cross-island contact the store
            // holds while the roster has not folded it in yet, which used to
            // land in the branch above and draw whoever holds that number on OUR
            // island. [crossIslandHost] and not [groupHost], so both are served
            // by the one fetch; it is non-null here by construction.
            //
            // ⚠ runCatching, like every other caller of fetchCard (e.g.
            // Session.addCrossIslandContactDetailed). It throws on three ordinary
            // paths, not just on a broken island: viaBestRoute rethrows the
            // IOException when no tunnel can be raised or the certificate was
            // refused, DuressGate.check() throws by design, and the card parse
            // asserts `identity_key` is there. Uncaught inside a LaunchedEffect
            // that is the composition going down, and this branch is no longer
            // the rare foreign-group-member sheet it was written for — an
            // ordinary 1:1 card reaches it now.
            //
            // ⚠ INSIDE the withContext and not around it: the blocking fetch is
            // the only thing that may be swallowed. Wrapping the withContext
            // itself would swallow the CancellationException too, and this effect
            // is cancelled every time the card is closed mid-fetch.
            val card = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { CrossIslandSender.fetchCard(crossIslandHost, uin) }.getOrNull()
            }
            if (card != null) {
                ciCardName = card.nickname
                ciCardStatus = card.statusMessage
            } else {
                // ⚠⚠ THE ANSWER MATTERS HERE TOO. This branch used to swallow
                // the failed fetch and draw on: a mistyped `номер@остров`
                // opened a card showing the bare digits, offline, with an Add
                // button, and nothing said the island had refused. That is
                // the same defect the ⚠ above records for our OWN island
                // (#483) — the fix simply never covered this branch, and it
                // started mattering the moment the search row began opening a
                // card instead of adding on the tap (#1032).
                notFound = true
            }
        }
    }

    // My own name for them wins over anything the island says. Kept separate
    // from `theirNickname` so the card can show both: "what I call them" on top,
    // "what they call themselves" underneath, otherwise a rename quietly hides
    // who you are actually talking to.
    val aliases by app.rcq.android.data.LocalStores.aliases.collectAsState()
    val alias = aliases[app.rcq.android.data.LocalStores.aliasKey(uin, crossIslandHost)]
    val theirNickname = profile?.nickname ?: ciCardName ?: contact?.nickname ?: "$uin"
    val nickname = alias ?: theirNickname
    var editAlias by remember(uin) { mutableStateOf<String?>(null) }
    val presence = contact?.presence ?: UserStatus.OFFLINE
    val statusMessage = profile?.status_message?.takeIf { it.isNotBlank() }
        ?: contact?.statusMessage?.takeIf { it.isNotBlank() }
        ?: ciCardStatus?.takeIf { it.isNotBlank() }
    val blocked = contact?.blocked == true

    // A bare number is ambiguous across islands: "#134" reaches a different
    // person depending on where you type it, so what goes to the clipboard is
    // always the full `uin@host` — a foreign contact's island, and OUR OWN
    // island for everybody else. It used to spell out only the foreign half,
    // which left the common case (passing a neighbour's number to somebody on
    // another island) as a string the person had to finish by hand, with the
    // island name nowhere on the screen (report #1025).
    val fullAddress = RcqFederation.fullAddress(uin, crossIslandHost ?: session.currentServer)

    fun copyUin() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("UIN", fullAddress))
        Toast.makeText(context, context.getString(R.string.common_uin_copied), Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize().background(c.bgPrimary)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = c.accent, modifier = Modifier.size(26.dp).clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.ci_title), color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Identity block.
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // A picture is the anchor of a contact card when there is one.
                // Cross-island contacts keep the glyph: the blob lives on their
                // island and presence does not cross either.
                PersonAvatar(
                    avatarMediaId, avatarMediaKey,
                    presence, session, 80.dp, animated = true, crossIsland = crossIslandHost != null,
                    onPictureClick = { showAvatar = true },
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(nickname, color = c.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    BadgeMark(contact?.badge, size = 18.dp)
                }
                if (alias != null && theirNickname != alias) {
                    Text(
                        stringResource(R.string.ci_their_name, theirNickname),
                        color = c.textSecondary, fontSize = 13.sp,
                    )
                }
                if (guestMember) {
                    Text(stringResource(R.string.group_member_guest), color = c.textSecondary, fontSize = 13.sp)
                } else Text(
                    stringResource(if (alias == null) R.string.ci_set_name else R.string.ci_change_name),
                    color = c.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable { editAlias = alias ?: "" },
                )
                // The status word itself is deliberately absent: the flower on the
                // avatar right above already says it, in colour, and repeating
                // "away" underneath is the same fact twice. What is NOT on the
                // avatar goes here — the island for a cross-island person, and
                // when someone was last around if they are offline.
                val sub = when {
                    crossIslandHost != null -> crossIslandHost
                    presence == UserStatus.OFFLINE && contact?.lastSeen != null ->
                        lastSeenPhrase(contact.lastSeen!!, contact.gender, context)
                    else -> null
                }
                sub?.let { Text(it, color = c.textSecondary, fontSize = 13.sp) }
                statusMessage?.let { Text(it, color = c.textPrimary, fontSize = 14.sp, textAlign = TextAlign.Center) }
            }

            Spacer(Modifier.height(12.dp))

            // Primary action. A contact gets "Message" (opens the 1:1); a
            // non-contact (this profile is opened from the add-contact search
            // BEFORE requesting) gets "Send request". Cross-island peers are
            // already added on resolve, so they only ever show Message.
            if (guestCopy || (guestMember && contact != null)) {
                // No Message, no request: see [guestCopy] and [guestMember] above.
            } else if (contact != null && !blocked) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accent)
                        .clickable { onOpenChat(uin) }.padding(14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.ci_message), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
            } else if (contact == null && notFound && !guestMember) {
                // Nobody holds this number. Say so instead of offering to write
                // to them: the island will refuse the request anyway, and the
                // refusal used to be swallowed silently.
                // ⚠ The `crossIslandHost == null` that used to be in this
                // condition is gone on purpose: an address on ANOTHER island
                // that does not resolve is the same fact, and it now reaches
                // here (the card fetch above records it). Without that, a
                // typo'd `номер@остров` drew a person who does not exist with
                // a button offering to add them.
                Text(
                    stringResource(
                        if (uin.toString().length < 3) R.string.ci_number_too_short
                        else R.string.ci_no_such_number
                    ),
                    color = c.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
            } else if (contact == null && crossIslandHost == null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (requestSent) c.bgSecondary else c.accent)
                        .clickable(enabled = !requestSent) {
                            // Our island's #134 while `134@somewhere.else` is
                            // already in the roster: two people, one thread key.
                            // Refuse rather than merge their histories.
                            if (session.clashesWithKnownNumber(uin, null)) {
                                Toast.makeText(context, context.getString(R.string.add_ci_number_clash, uin), Toast.LENGTH_LONG).show()
                                return@clickable
                            }
                            // ⚠ The button used to flip to "request sent" on the
                            // spot, outside the coroutine, so it said so whether
                            // or not the island had taken the request — the 404
                            // for a number nobody holds went straight into
                            // runCatching and the user was told it had gone
                            // through (#483, point 4: "не появляется в
                            // исходящих"). Report what happened, not what was
                            // attempted.
                            scope.launch {
                                val ok = runCatching { session.addContact(uin) }.isSuccess
                                if (ok) requestSent = true
                                else Toast.makeText(context, context.getString(R.string.ci_request_failed), Toast.LENGTH_LONG).show()
                            }
                            // The request is on its way, so the search that led
                            // here has done its job — don't reopen it behind us
                            // on the way back to the home screen.
                            AddSheet.close()
                        }.padding(14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (requestSent) R.string.add_request_sent else R.string.ci_send_request),
                        color = if (requestSent) c.textSecondary else Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
            } else if (contact == null && crossIslandHost != null) {
                // Cross-island add. ⚠ This used to flip to "Request sent" on the
                // spot, outside the coroutine — and there was no request at all:
                // the add was a purely local row and the peer was never told
                // (spec §5f). It now deposits a `contactreq` to their island and
                // reports what actually happened: sent, added-but-undelivered,
                // or nothing at all.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (requestSent) c.bgSecondary else c.accent)
                        .clickable(enabled = !requestSent) {
                            scope.launch {
                                when (runCatching { session.addCrossIslandContactDetailed(uin, crossIslandHost) }
                                    .getOrDefault(Session.CiAdd.FAILED)) {
                                    Session.CiAdd.SENT -> requestSent = true
                                    Session.CiAdd.ADDED_ONLY -> {
                                        requestSent = true
                                        Toast.makeText(context, context.getString(R.string.ci_request_not_delivered), Toast.LENGTH_LONG).show()
                                    }
                                    Session.CiAdd.CLOSED_ISLAND ->
                                        Toast.makeText(context, context.getString(R.string.ci_closed_island), Toast.LENGTH_LONG).show()
                                    Session.CiAdd.FAILED ->
                                        Toast.makeText(context, context.getString(R.string.ci_request_failed), Toast.LENGTH_LONG).show()
                                }
                            }
                        }.padding(14.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(if (requestSent) R.string.add_request_sent else R.string.random_add_contact),
                        color = if (requestSent) c.textSecondary else Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // UIN row (copyable).
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).clickable { copyUin() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("UIN", color = c.textSecondary, fontSize = 12.sp)
                    // What the row COPIES, spelled out, island and all. This is
                    // the one screen whose subject is "who is this, exactly",
                    // so the suffix the chat list hides is information here,
                    // and showing it is also the only way the person learns
                    // what to type when they pass the number on (#1025).
                    Text(fullAddress, color = c.textMono, fontSize = 15.sp)
                }
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.common_copy_uin), tint = c.textSecondary, modifier = Modifier.size(18.dp))
            }

            // Safety-number-changed warning (re-register / new device / MITM).
            //
            // Tapping it opens the code, which is what the text tells you to do.
            // It used to be inert: the copy said "open it to compare again", the
            // banner ignored taps, and the thing to open was an unlabelled row
            // further down the screen (#407). The icon is amber, not red: this is
            // a warning to check something, not a prohibition, and the reporter
            // said as much.
            if (identityChanged && !guestMember) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)
                        .clickable {
                            showSafety = true
                            safetyLoading = true
                            session.acknowledgePeerIdentity(uin)
                            identityChanged = false
                            scope.launch {
                                safetyNumber = session.safetyNumber(uin)
                                safetyLoading = false
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, null, tint = WARNING, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.ci_identity_changed), color = c.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                }
            }

            // Profile fields (only those the server let us see). Labels are
            // resolved here because buildList's lambda isn't composable.
            val lblAge = stringResource(R.string.pe_age)
            val lblGender = stringResource(R.string.common_gender)
            val lblCity = stringResource(R.string.common_city)
            val lblCountry = stringResource(R.string.common_country)
            val lblAbout = stringResource(R.string.common_about)
            val lblInterests = stringResource(R.string.common_interests)
            val lblHomepage = stringResource(R.string.common_homepage)
            val lblRealName = stringResource(R.string.common_real_name)
            val genderValue = when (profile?.gender?.lowercase()) {
                "male", "m" -> stringResource(R.string.common_male)
                "female", "f" -> stringResource(R.string.common_female)
                "other" -> stringResource(R.string.common_other)
                else -> null
            }
            val fields = buildList {
                profile?.age?.takeIf { it > 0 }?.let { add(lblAge to it.toString()) }
                genderValue?.let { add(lblGender to it) }
                profile?.city?.takeIf { it.isNotBlank() }?.let { add(lblCity to it) }
                profile?.country?.takeIf { it.isNotBlank() }?.let { add(lblCountry to it) }
                profile?.about?.takeIf { it.isNotBlank() }?.let { add(lblAbout to it) }
                // These three the island has been returning all along and this
                // screen simply never read, so a person could fill in their
                // interests or their site and no one on Android would ever see
                // it. iOS shows all of them.
                profile?.interests?.takeIf { it.isNotEmpty() }
                    ?.let { add(lblInterests to it.joinToString(", ")) }
                profile?.homepage?.takeIf { it.isNotBlank() }?.let { add(lblHomepage to it) }
                listOfNotNull(
                    profile?.first_name?.takeIf { it.isNotBlank() },
                    profile?.last_name?.takeIf { it.isNotBlank() },
                ).joinToString(" ").takeIf { it.isNotBlank() }?.let { add(lblRealName to it) }
            }
            if (fields.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)) {
                    fields.forEachIndexed { i, (label, value) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 16.dp).background(c.divider))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                            Text(label, color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.width(96.dp))
                            Text(value, color = c.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Actions. None on a guest copy's card (decision D5).
            if (!guestMember) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary)) {
                InfoAction(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(if (isFav) R.string.ci_remove_fav else R.string.ci_add_fav)) { LocalStores.toggleFavorite(thread) }
                InfoDivider()
                InfoAction(Icons.Filled.NotificationsOff, stringResource(if (isMuted) R.string.ci_unmute else R.string.ci_mute)) { LocalStores.toggleMute(thread) }
                InfoDivider()
                InfoAction(Icons.Outlined.Block, stringResource(if (blocked) R.string.ci_unblock else R.string.ci_block), danger = !blocked) {
                    scope.launch { runCatching { session.toggleBlock(uin) } }
                }
                InfoDivider()
                InfoAction(Icons.Filled.Lock, stringResource(R.string.ci_safety)) {
                    showSafety = true
                    safetyLoading = true
                    // Opening it to re-check counts as acknowledging the change.
                    if (identityChanged) {
                        session.acknowledgePeerIdentity(uin)
                        identityChanged = false
                    }
                    scope.launch {
                        safetyNumber = session.safetyNumber(uin)
                        safetyLoading = false
                    }
                }
            }

            // Only offer to remove someone who is actually in the roster.
            // `contact` is null for a person whose request you never accepted
            // (and for a cross-island group member), and the screen already
            // branches on that above to show "Add contact" instead — but the
            // remove button was drawn unconditionally, so the profile of
            // someone who is not your contact offered to delete them (#425).
            if (contact != null && !guestMember) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bgSecondary).clickable { confirmRemove = true }.padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.PersonRemove, null, tint = DANGER, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.ci_remove), color = DANGER, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    editAlias?.let { current ->
        var draft by remember(current) { mutableStateOf(current) }
        RcqSheet(onDismiss = { editAlias = null }, title = stringResource(R.string.ci_set_name)) {
            Text(stringResource(R.string.ci_name_hint), color = c.textSecondary, fontSize = 12.sp)
            SheetGap(8)
            RcqField(
                value = draft,
                onValueChange = { if (it.length <= 48) draft = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = theirNickname,
            )
            SheetGap()
            SheetActionRow(stringResource(R.string.common_save)) {
                app.rcq.android.data.LocalStores.setAlias(uin, draft, crossIslandHost)
                editAlias = null
            }
            // Clearing the alias is not a cancel — it drops the name I gave them
            // and only then closes, so it stays a row of its own.
            if (alias != null) {
                SheetActionRow(stringResource(R.string.ci_clear_name), dimmed = true) {
                    app.rcq.android.data.LocalStores.setAlias(uin, null, crossIslandHost)
                    editAlias = null
                }
            }
            SheetActionRow(stringResource(R.string.common_cancel), dimmed = true) { editAlias = null }
        }
    }

    if (confirmRemove) {
        // Same dialog as the home screen. This one used to skip the question
        // entirely and keep the history by default, so where you tapped Remove
        // decided what happened to your messages.
        RemoveContactDialog(
            nickname = nickname,
            onDismiss = { confirmRemove = false },
            onRemove = { alsoDelete ->
                confirmRemove = false
                scope.launch {
                    runCatching { session.removeContact(uin, alsoDeleteMessages = alsoDelete) }
                    onRemoved()
                }
            },
        )
    }

    if (showSafety) {
        RcqSheet(onDismiss = { showSafety = false }, title = stringResource(R.string.ci_safety_title)) {
            when {
                safetyLoading -> Text(stringResource(R.string.ci_safety_loading), color = c.textSecondary)
                safetyNumber == null -> Text(stringResource(R.string.ci_safety_unavailable), color = c.textSecondary)
                else -> {
                    Text(
                        safetyNumber!!,
                        color = c.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ci_safety_body), color = c.textSecondary, fontSize = 13.sp)
                }
            }
            SheetGap()
            SheetActionRow(stringResource(R.string.common_close)) { showSafety = false }
        }
    }

    if (showAvatar) {
        // The same cachedImage/fetchImage pair PersonAvatar uses, so the bytes
        // the avatar already decoded are normally sitting in memory and the
        // viewer opens on the first frame instead of after a round trip.
        // ⚠ No save and no share: this is somebody's face, not a picture they
        // sent me. See `showActions` on the viewer.
        val bytes by produceState<ByteArray?>(session.cachedImage(avatarMediaId), avatarMediaId, avatarMediaKey) {
            value = if (!avatarMediaId.isNullOrEmpty() && !avatarMediaKey.isNullOrEmpty()) {
                session.fetchImage(avatarMediaId, avatarMediaKey)
            } else null
        }
        // Nothing to show yet (or nothing at all): stay closed rather than open
        // a black rectangle. A tap can only reach here when a picture is drawn,
        // so this is the fetch still in flight, not a dead end.
        bytes?.let {
            FullscreenImageViewer(
                bytes = it,
                showActions = false,
                onDismiss = { showAvatar = false },
            )
        }
    }
}

/** A tap-row for a sheet that brings its own body: [RcqAskSheet] lays out its
 *  actions and appends a cancel by itself, [RcqSheet] leaves both to the caller.
 *  Same shape and weights as the rows inside [RcqAskSheet]. */
@Composable
private fun SheetActionRow(label: String, dimmed: Boolean = false, onClick: () -> Unit) {
    val c = RcqTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (dimmed) c.textSecondary else c.accent,
            fontSize = 16.sp,
            fontWeight = if (dimmed) FontWeight.Normal else FontWeight.Medium,
        )
    }
}

@Composable
private fun InfoAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = RcqTheme.colors
    val tint = if (danger) DANGER else c.accent
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (danger) DANGER else c.textPrimary, fontSize = 16.sp)
    }
}

@Composable
private fun InfoDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).padding(start = 48.dp).background(RcqTheme.colors.divider))
}
