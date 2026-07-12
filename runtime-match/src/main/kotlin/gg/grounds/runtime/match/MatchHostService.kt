package gg.grounds.runtime.match

import gg.grounds.grpc.match.MatchHostGrpc
import gg.grounds.grpc.match.StartMatchReply
import gg.grounds.grpc.match.StartMatchRequest
import io.grpc.stub.StreamObserver
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * The gRPC side of the matchmaker's push: turn a [StartMatchRequest] into a [PushedMatch] and hand
 * it to [MatchRegistry.accept].
 *
 * Always replies via [StreamObserver.onNext] followed by [StreamObserver.onCompleted] — including
 * on a refusal — because leaving the call hanging or throwing out of this method gives the
 * matchmaker no way to distinguish "refused" from "lost".
 */
class MatchHostService(private val registry: MatchRegistry, private val handler: MatchHandler) :
    MatchHostGrpc.MatchHostImplBase() {
    private val logger = LoggerFactory.getLogger(MatchHostService::class.java)

    override fun startMatch(
        request: StartMatchRequest,
        responseObserver: StreamObserver<StartMatchReply>,
    ) {
        val teams = request.teamsList
        if (teams.all { it.playerIdsList.isEmpty() }) {
            reply(responseObserver, accepted = false, reason = "empty roster")
            return
        }

        val roster =
            try {
                teams.map { team -> team.playerIdsList.map(UUID::fromString) }
            } catch (e: IllegalArgumentException) {
                reply(responseObserver, accepted = false, reason = "malformed player id")
                return
            }

        val match = PushedMatch(request.matchId, request.modeId, roster)
        if (registry.accept(match, handler)) {
            reply(responseObserver, accepted = true, reason = "")
        } else {
            logger.warn("Match host refused match {}", match.matchId)
            reply(responseObserver, accepted = false, reason = "match host refused the match")
        }
    }

    private fun reply(
        observer: StreamObserver<StartMatchReply>,
        accepted: Boolean,
        reason: String,
    ) {
        observer.onNext(
            StartMatchReply.newBuilder().setAccepted(accepted).setReason(reason).build()
        )
        observer.onCompleted()
    }
}
