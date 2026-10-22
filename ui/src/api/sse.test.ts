import { describe, expect, it } from 'vitest';
import { createSseParser, type SseMessage } from './sse';

function collect() {
  const messages: SseMessage[] = [];
  return { messages, feed: createSseParser((m) => messages.push(m)) };
}

describe('server-sent events parser', () => {
  it('reads a message with an id, an event name and data', () => {
    const { messages, feed } = collect();
    feed('id: 7\nevent: notification\ndata: {"a":1}\n\n');
    expect(messages).toEqual([{ id: '7', event: 'notification', data: '{"a":1}' }]);
  });

  it('copes with a message split across chunks, even in the middle of a word', () => {
    const { messages, feed } = collect();
    feed('id: 8\nevent: notif');
    feed('ication\nda');
    expect(messages).toEqual([]);
    feed('ta: hello\n');
    feed('\n');
    expect(messages).toEqual([{ id: '8', event: 'notification', data: 'hello' }]);
  });

  it('ignores comment lines (the keep-alive) and messages without data', () => {
    const { messages, feed } = collect();
    feed(': keep-alive\n\n');
    feed('event: ping\n\n');
    expect(messages).toEqual([]);
  });

  it('joins several data lines with a newline and defaults the event name to "message"', () => {
    const { messages, feed } = collect();
    feed('data: one\ndata: two\n\n');
    expect(messages).toEqual([{ id: undefined, event: 'message', data: 'one\ntwo' }]);
  });

  it('understands Windows line endings, including a CR and LF split across chunks', () => {
    const { messages, feed } = collect();
    feed('data: x\r');
    feed('\n\r\n');
    expect(messages).toEqual([{ id: undefined, event: 'message', data: 'x' }]);
  });

  it('keeps the last id for the messages that follow it', () => {
    const { messages, feed } = collect();
    feed('id: 1\ndata: a\n\n');
    feed('data: b\n\n');
    expect(messages.map((m) => m.id)).toEqual(['1', '1']);
  });
});
