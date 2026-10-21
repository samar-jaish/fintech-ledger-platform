import { ApiError } from '../api/client';

/** Shows what went wrong in words a person can act on. The API's own message is used when there is one. */
export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const message = error instanceof ApiError ? error.message : 'Something went wrong. Please try again.';
  return (
    <p className="error" role="alert">
      {message}
    </p>
  );
}
