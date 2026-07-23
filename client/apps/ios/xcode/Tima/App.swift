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
            runtime = IosPhase1Runtime(baseUrl: baseURL)
            Task { try? await runtime?.restoreSession() }
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
        Task { try? await runtime?.apns.didRegisterForRemoteNotifications(deviceToken: deviceToken) }
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        Task { try? await runtime?.apns.didFailToRegisterForRemoteNotifications() }
    }
}
