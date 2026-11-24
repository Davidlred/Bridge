package app.thebridge.data

import app.thebridge.data.remote.ApiClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

// --- Game Night Arcade: 1v1 matches, brackets and the Everyone games. The server runs every rule; these are its views. ---

@Serializable data class PersonDto(val id: String, val name: String = "")

/** The challenge card: the same for everyone, worded on each phone ("Your turn", "Sam's turn"). */
@Serializable data class MatchCardDto(
    val matchId: String, val game: String, val source: String = "chat_challenge", val bracketId: String? = null,
    val players: List<PersonDto> = emptyList(), val turn: String? = null, val status: String = "waiting", val winnerId: String? = null,
    val result: String? = null, val scores: Map<String, Int> = emptyMap(), val scored: Boolean = false, val toBeat: Int? = null, val held: Boolean = false,
)

@Serializable data class MatchDto(
    val id: String, val matchId: String = "", val game: String, val allianceId: String = "", val source: String = "chat_challenge", val bracketId: String? = null,
    val players: List<PersonDto> = emptyList(), val turn: String? = null, val status: String = "waiting", val winnerId: String? = null, val result: String? = null,
    val scores: Map<String, Int> = emptyMap(), val scored: Boolean = false, val toBeat: Int? = null, val held: Boolean = false,
    val mode: String = "duel_turns", val round: Int? = null, val version: Int = 0, val you: String = "", val known: Boolean = true, val now: Long = 0,
    val lastMoveAt: Long = 0, val canClaim: Boolean = false, val view: JsonElement? = null,
) {
    val opponent: PersonDto? get() = players.firstOrNull { it.id != you }
    val done: Boolean get() = status == "done"
    val myTurn: Boolean get() = !done && turn == you
}

@Serializable data class BracketMatchDto(
    val id: String, val slot: Int = 0, val a: PersonDto? = null, val b: PersonDto? = null, val status: String = "waiting", val mode: String = "duel_live",
    val turn: String? = null, val winnerId: String? = null, val result: String? = null, val scores: List<Int> = emptyList(), val mine: Boolean = false,
)
@Serializable data class ByeDto(val user: PersonDto? = null, val vs: List<PersonDto?>? = null)
@Serializable data class BracketRoundDto(val round: Int, val label: String = "round", val matches: List<BracketMatchDto> = emptyList(), val bye: ByeDto? = null)
@Serializable data class BracketDto(
    val id: String, val allianceId: String = "", val game: String, val status: String = "active", val round: Int = 1, val roundCount: Int = 1,
    val deadline: Long = 0, val votes: Int = 0, val voters: Int = 0, val champion: PersonDto? = null, val players: List<PersonDto?> = emptyList(),
    val stages: List<String> = emptyList(), val rounds: List<BracketRoundDto> = emptyList(), val myMatchId: String? = null, val playing: Boolean = false,
)

@Serializable data class LivePlayerDto(val userId: String, val name: String = "", val ready: Boolean = false, val absent: Boolean = false, val watching: Boolean = false)
@Serializable data class LiveResultDto(val userId: String, val name: String = "", val score: Int = 0, val place: Int = 1)
@Serializable data class LiveGameDto(
    val id: String, val game: String, val allianceId: String = "", val phase: String = "lobby", val endsAt: Long = 0, val now: Long = 0, val max: Int = 15, val min: Int = 2,
    val players: List<LivePlayerDto> = emptyList(), val world: JsonElement? = null, val results: List<LiveResultDto>? = null, val lowerWins: Boolean = false,
)

@Serializable private data class MatchResponse(val match: MatchDto)
@Serializable private data class BracketResponse(val bracket: BracketDto)
@Serializable private data class LiveResponse(val game: LiveGameDto? = null)
@Serializable private data class CardsResponse(val matches: List<MatchCardDto>)

/** The games this version of the app can play. A match in any other game shows "Update The Bridge to play this". */
val PLAYABLE = setOf("four", "word", "scramble", "darts", "fleet", "cup", "pool", "hoops", "golf", "push", "drift", "whose")

@Singleton
class ArcadeRepository @Inject constructor(private val api: ApiClient) {
    val json get() = api.json

    suspend fun match(id: String): MatchDto = json.decodeFromString<MatchResponse>(api.authed("GET", "/v1/matches/$id")).match
    suspend fun move(id: String, move: JsonObject): MatchDto =
        json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/matches/$id/move", buildJsonObject { put("move", move) })).match
    suspend fun setMode(id: String, live: Boolean): MatchDto =
        json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/matches/$id/mode", buildJsonObject { put("mode", if (live) "duel_live" else "duel_turns") })).match
    suspend fun resign(id: String): MatchDto = json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/matches/$id/resign")).match
    suspend fun claim(id: String): MatchDto = json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/matches/$id/claim")).match
    suspend fun rematch(id: String): MatchDto = json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/matches/$id/rematch")).match
    suspend fun challenge(allianceId: String, game: String, opponentId: String): MatchDto =
        json.decodeFromString<MatchResponse>(api.authed("POST", "/v1/alliances/$allianceId/challenges", buildJsonObject { put("game", game); put("opponentId", opponentId) })).match
    suspend fun myMatches(): List<MatchCardDto> = json.decodeFromString<CardsResponse>(api.authed("GET", "/v1/me/matches")).matches
    suspend fun bracket(id: String): BracketDto = json.decodeFromString<BracketResponse>(api.authed("GET", "/v1/brackets/$id")).bracket
    suspend fun live(allianceId: String): LiveGameDto? = json.decodeFromString<LiveResponse>(api.authed("GET", "/v1/alliances/$allianceId/live")).game
}
