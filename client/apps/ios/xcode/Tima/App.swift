import SwiftUI
import UserNotifications
import TimaIosApp

@main
struct TimaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            ContentView(runtime: delegate.runtime)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    private(set) var runtime: IosPhase1Runtime?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        if let baseURL = Bundle.main.object(forInfoDictionaryKey: "TimaBaseURL") as? String,
           !baseURL.isEmpty {
            #if DEBUG
            let debugBuild = true
            #else
            let debugBuild = false
            #endif
            let developmentFlag =
                Bundle.main.object(forInfoDictionaryKey: "TimaEnableDevelopmentAuth")
            let explicitDevelopmentAuth =
                (developmentFlag as? Bool) == true ||
                (developmentFlag as? String)?.lowercased() == "true"
            runtime = IosPhase1Runtime(
                baseUrl: baseURL,
                debugBuild: debugBuild,
                explicitDevelopmentAuth: explicitDevelopmentAuth
            )
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) {
                granted, _ in
                if granted {
                    DispatchQueue.main.async { application.registerForRemoteNotifications() }
                }
            }
        }
        return true
    }

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        Task { try? await runtime?.apnsDidRegister(deviceToken: deviceToken) }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task { try? await runtime?.apnsDidFail() }
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        Task {
            try? await runtime?.applicationDidBecomeActive()
            await MainActor.run {
                NotificationCenter.default.post(name: .timaDidCatchUp, object: nil)
            }
        }
    }

    func applicationWillTerminate(_ application: UIApplication) {
        runtime?.close()
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        // Generic wake metadata only. Private plaintext/preview fields are deliberately discarded.
        let allowed = ["type", "chat_id", "encrypted", "collapse_key", "event_id"]
        let payload = allowed.reduce(into: [String: String]()) { values, key in
            if let value = userInfo[key] as? String {
                values[key] = value
            } else if let value = userInfo[key] as? Bool {
                values[key] = String(value)
            }
        }
        Task {
            do {
                try await runtime?.didReceiveApnsWake(payload: payload)
                completionHandler(.newData)
            } catch {
                completionHandler(.failed)
            }
        }
    }
}

extension Notification.Name {
    static let timaDidCatchUp = Notification.Name("com.tima.client.didCatchUp")
}
