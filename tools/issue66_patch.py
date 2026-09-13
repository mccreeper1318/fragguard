from pathlib import Path

path = Path("src/main/java/org/pinnaclesmp/fragguard/Database.java")
text = path.read_text(encoding="utf-8")

old_select = """                SELECT happened_at, actor_name, world, x, y, z, action, before_data, after_data
                FROM block_changes
"""
new_select = """                SELECT happened_at, actor_name, world, x, y, z, action, before_data, after_data,
                       before_entity_data, after_entity_data
                FROM block_changes
"""
if text.count(old_select) != 1:
    raise SystemExit(f"Expected exactly one lookup SELECT, found {text.count(old_select)}")
text = text.replace(old_select, new_select)

old_row = """                    rows.add(new LookupRow(resultSet.getLong(\"happened_at\"), resultSet.getString(\"actor_name\"),
                            resultSet.getString(\"world\"), resultSet.getInt(\"x\"), resultSet.getInt(\"y\"),
                            resultSet.getInt(\"z\"), ChangeAction.fromStorageId(resultSet.getString(\"action\")),
                            resultSet.getString(\"before_data\"), resultSet.getString(\"after_data\")));
"""
new_row = """                    rows.add(new LookupRow(resultSet.getLong(\"happened_at\"), resultSet.getString(\"actor_name\"),
                            resultSet.getString(\"world\"), resultSet.getInt(\"x\"), resultSet.getInt(\"y\"),
                            resultSet.getInt(\"z\"), ChangeAction.fromStorageId(resultSet.getString(\"action\")),
                            resultSet.getString(\"before_data\"), resultSet.getString(\"after_data\"),
                            resultSet.getBytes(\"before_entity_data\"), resultSet.getBytes(\"after_entity_data\")));
"""
if text.count(old_row) != 1:
    raise SystemExit(f"Expected exactly one LookupRow mapping, found {text.count(old_row)}")
text = text.replace(old_row, new_row)

path.write_text(text, encoding="utf-8")
