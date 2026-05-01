// Meshtastic — open source mesh radio
// Copyright © 2026 Meshtastic LLC
// Licensed under the GPL-3.0-or-later license (see LICENSE)
// SPDX-License-Identifier: GPL-3.0-or-later

import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
