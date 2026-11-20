-- M3-3-1, blueprint 7.3: every message keeps the data class of the call that took it to a model, and a conversation
-- keeps a watermark, the highest class it has sent to a model. The watermark only rises: when history is cut short or
-- summed up, the classes of what went may go with it, so the watermark keeps them. A message refused before it reached
-- a model is never stored, so it raises nothing. A message's class never changes once stored, since V3 lets a message
-- change only through its events, and an answer's ending sets no class.
--
-- The messages from before M3 had no class. They take public, the lowest: every call from now on takes the
-- application's default class as well, so nothing they held is sent to a model below that.

ALTER TABLE conversation_messages ADD COLUMN data_class text NOT NULL DEFAULT 'public'
    CHECK (data_class IN ('public', 'internal', 'personal', 'sensitive'));
ALTER TABLE conversation_messages ALTER COLUMN data_class DROP DEFAULT;

ALTER TABLE conversation_conversations ADD COLUMN data_class_watermark text NOT NULL DEFAULT 'public'
    CHECK (data_class_watermark IN ('public', 'internal', 'personal', 'sensitive'));

-- A class's place in the order, lowest first.
CREATE FUNCTION conversation_class_rank(data_class text) RETURNS integer
    LANGUAGE sql IMMUTABLE
    SET search_path = engine, pg_temp
    RETURN array_position(ARRAY['public', 'internal', 'personal', 'sensitive'], data_class);

CREATE FUNCTION conversation_keep_watermark() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = engine, pg_temp
AS $$
BEGIN
    IF conversation_class_rank(NEW.data_class_watermark) < conversation_class_rank(OLD.data_class_watermark) THEN
        RAISE EXCEPTION 'Conversation % has sent % data to a model, so its watermark cannot go down to %',
            OLD.conversation_id, OLD.data_class_watermark, NEW.data_class_watermark;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER watermark_only_rises BEFORE UPDATE OF data_class_watermark ON conversation_conversations
    FOR EACH ROW EXECUTE FUNCTION conversation_keep_watermark();
