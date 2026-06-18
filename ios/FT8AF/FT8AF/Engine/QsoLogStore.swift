import FT8Engine
import Foundation

/// Simple file-based persistence for QSO records. Stores the log as a JSON
/// array in the app's documents directory. Loaded on startup and saved after
/// each new QSO.
enum QsoLogStore {
    private static let fileName = "qso_log.json"

    static func fileURL() -> URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent(fileName)
    }

    static func load() -> [QsoRecord] {
        let url = fileURL()
        guard FileManager.default.fileExists(atPath: url.path) else { return [] }
        do {
            let data = try Data(contentsOf: url)
            var records = try JSONDecoder().decode([QsoRecord].self, from: data)
            // Ensure every record has a unique ID (fixup for any legacy data).
            for i in records.indices where records[i].id == nil {
                records[i].id = Int64(i + 1)
            }
            return records
        } catch {
            return []
        }
    }

    static func save(_ records: [QsoRecord]) {
        do {
            let data = try JSONEncoder().encode(records)
            try data.write(to: fileURL(), options: .atomic)
        } catch {
            // Best-effort: log persistence failure is non-fatal.
        }
    }
}
