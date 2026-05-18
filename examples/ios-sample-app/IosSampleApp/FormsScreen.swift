import SwiftUI

/// Minimal Forms screen mirroring the Android sample app's Forms tab.
/// Name + Email text fields, a Submit button, a Clear All button, and a
/// submission-result label that re-renders the entered values. Used by the
/// `evals/clipboard-round-trip` iOS trail to verify that
/// `mobile_setClipboard` + `mobile_pasteClipboard` survive a paste into a
/// real SwiftUI `TextField`.
struct FormsScreen: View {
  @State private var name: String = ""
  @State private var email: String = ""
  @State private var submissionResult: String = ""
  @FocusState private var focusedField: Field?

  enum Field: Hashable {
    case name
    case email
  }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 12) {
        TextField("Name", text: $name)
          .textFieldStyle(.roundedBorder)
          .accessibilityIdentifier("field_name")
          .focused($focusedField, equals: .name)
          .submitLabel(.next)

        TextField("Email", text: $email)
          .textFieldStyle(.roundedBorder)
          .accessibilityIdentifier("field_email")
          .keyboardType(.emailAddress)
          .textInputAutocapitalization(.never)
          .focused($focusedField, equals: .email)
          .submitLabel(.done)

        HStack(spacing: 12) {
          Button(action: submit) {
            Text("Submit")
              .frame(maxWidth: .infinity)
          }
          .buttonStyle(.borderedProminent)

          Button(action: clearAll) {
            Text("Clear All")
              .frame(maxWidth: .infinity)
          }
          .buttonStyle(.bordered)
        }

        if !submissionResult.isEmpty {
          Spacer().frame(height: 8)
          Text("Submission Result:")
            .font(.system(size: 18))
          Text(submissionResult)
            .accessibilityIdentifier("tv_submission_result")
        }

        Spacer()
      }
      .padding(16)
    }
  }

  private func submit() {
    submissionResult = "Name: \(name)\nEmail: \(email)"
    focusedField = nil
  }

  private func clearAll() {
    name = ""
    email = ""
    submissionResult = ""
  }
}

#Preview {
  FormsScreen()
}
