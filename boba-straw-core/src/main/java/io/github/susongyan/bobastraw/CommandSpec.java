package io.github.susongyan.bobastraw;

/** Internal metadata, not an extension SPI. No attribute authorizes command replay. */
final class CommandSpec {
    enum Keys { NONE, FIRST, ALL, PAIRS, FIRST_TWO, SCRIPT, UNKNOWN }
    enum Connection { ORDINARY, BLOCKING, STATEFUL, PUBSUB }
    enum Access { READ_ONLY, WRITE, UNKNOWN }

    final String name;
    final Keys keys;
    final Connection connection;
    final Access access;
    // Null means not yet recorded, not "supported on every version".
    final String since;

    CommandSpec(String name, Keys keys, Connection connection, Access access, String since) {
        this.name = name;
        this.keys = keys;
        this.connection = connection;
        this.access = access;
        this.since = since;
    }

    Integer slot(CommandArgs args) {
        int start = 0;
        int end = args.size();
        int step = 1;
        switch (keys) {
            case NONE:
                return null;
            case FIRST:
                require(end >= 1);
                end = 1;
                break;
            case FIRST_TWO:
                require(end >= 2);
                end = 2;
                break;
            case ALL:
                require(end >= 1);
                break;
            case PAIRS:
                require(end > 0 && end % 2 == 0);
                step = 2;
                break;
            case SCRIPT:
                require(end >= 2);
                int count;
                try {
                    count = Integer.parseInt(args.control(1));
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Invalid script numkeys", error);
                }
                require(count >= 0 && count <= end - 2);
                start = 2;
                end = start + count;
                break;
            default:
                throw new IllegalArgumentException("No key metadata for " + name);
        }
        Integer slot = null;
        for (int index = start; index < end; index += step) {
            int next = args.slot(index);
            if (slot != null && slot.intValue() != next) {
                throw new IllegalArgumentException("CROSSSLOT: all command keys must hash to the same slot");
            }
            slot = next;
        }
        return slot;
    }

    private void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid key arguments for " + name);
        }
    }
}
