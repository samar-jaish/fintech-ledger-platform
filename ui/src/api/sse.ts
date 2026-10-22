// A small reader for "server-sent events", the text format a server uses to push messages over one long HTTP response:
//
//   id: 12
//   event: notification
//   data: {"amount": 10}
//   (a blank line ends the message)
//
// The browser's own EventSource cannot send an Authorization header, so the stream is read with fetch and this parser.

export interface SseMessage {
  /** The message id, if the server sent one. A client sends the last one back (Last-Event-ID) when it reconnects. */
  id?: string;
  event: string;
  data: string;
}

/** Returns a function to feed with chunks of text as they arrive (chunks may split a line anywhere). */
export function createSseParser(onMessage: (message: SseMessage) => void): (chunk: string) => void {
  let pending = '';
  let sawCarriageReturn = false;
  let id: string | undefined;
  let event = '';
  let data: string[] = [];

  function line(text: string) {
    if (text === '') {
      if (data.length > 0) onMessage({ id, event: event || 'message', data: data.join('\n') });
      event = '';
      data = [];
      return;
    }
    if (text.startsWith(':')) return; // a comment: the server's keep-alive
    const colon = text.indexOf(':');
    const field = colon === -1 ? text : text.slice(0, colon);
    let value = colon === -1 ? '' : text.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'data') data.push(value);
    else if (field === 'event') event = value;
    else if (field === 'id' && !value.includes('\0')) id = value;
  }

  return (chunk: string) => {
    for (const ch of chunk) {
      if (ch === '\n' && sawCarriageReturn) {
        sawCarriageReturn = false; // the second half of a CRLF pair: the line already ended
        continue;
      }
      sawCarriageReturn = ch === '\r';
      if (ch === '\n' || ch === '\r') {
        line(pending);
        pending = '';
      } else {
        pending += ch;
      }
    }
  };
}
