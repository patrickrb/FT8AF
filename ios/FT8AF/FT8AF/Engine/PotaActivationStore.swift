import FT8Engine
import Foundation

/// File-based persistence for POTA activation sessions, exactly like
/// `QsoLogStore`: a JSON array in the app's documents directory, loaded when
/// the POTA screen first appears and saved after every change. This is what
/// makes the History tab (and a still-open activation) survive restarts.
enum PotaActivationStore {
    private static let fileName = "pota_activations.json"

    static func fileURL() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(fileName)
    }

    static func load() -> [PotaActivationRecord] {
        let url = fileURL()
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        do {
            let data = try Data(contentsOf: url)
            return try JSONDecoder().decode([PotaActivationRecord].self, from: data)
        } catch {
            return []
        }
    }

    static func save(_ activations: [PotaActivationRecord]) {
        do {
            let data = try JSONEncoder().encode(activations)
            try data.write(to: fileURL(), options: .atomic)
        } catch {
            // Best-effort: activation-history persistence failure is non-fatal.
        }
    }
}
