import CryptoKit
import SwiftUI
import TimaIosApp

struct ContentView: View {
    let runtime: IosPhase1Runtime?
    @State private var status = "Tima Phase 1 platform shell"

    var body: some View {
        VStack(spacing: 16) {
            Text(status).multilineTextAlignment(.center)
            Button("Prepare App Attest enrollment") {
                guard let runtime else {
                    status = "Blocked: set TimaBaseURL in Info.plist"
                    return
                }
                Task {
                    do {
                        let digestBytes = Array(
                            SHA256.hash(data: Data("tima-app-attest-enrollment".utf8))
                        )
                        let challenge = KotlinByteArray(size: Int32(digestBytes.count))
                        for (index, byte) in digestBytes.enumerated() {
                            challenge.set(index: Int32(index), value: Int8(bitPattern: byte))
                        }
                        let enrollment = try await runtime.appAttest.prepareEnrollment(
                            serverChallengeSha256: challenge
                        )
                        status = "Enrollment object ready for key \(enrollment.keyId)"
                    } catch {
                        status = "Blocked: \(error.localizedDescription)"
                    }
                }
            }
            Button("Register APNs token") {
                guard let runtime else {
                    status = "Blocked: set TimaBaseURL in Info.plist"
                    return
                }
                Task {
                    do {
                        try await runtime.phase1.registerCurrentPushToken()
                        status = "APNs token registered"
                    } catch {
                        status = "Blocked: \(error.localizedDescription)"
                    }
                }
            }
        }
        .padding(24)
    }
}
