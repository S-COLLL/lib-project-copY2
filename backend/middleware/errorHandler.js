const ApiError = require('../utils/ApiError');

// The database host can't be found or reached (e.g. a cloud database that was powered off).
const DB_UNREACHABLE = new Set(['ENOTFOUND', 'EAI_AGAIN', 'ECONNREFUSED', 'ETIMEDOUT', 'ECONNRESET', 'PROTOCOL_CONNECTION_LOST']);

function notFound(req, res, next) {
  next(new ApiError(404, `Route not found: ${req.method} ${req.originalUrl}`));
}

// eslint-disable-next-line no-unused-vars
function errorHandler(err, req, res, next) {
  let status = err instanceof ApiError ? err.statusCode : 500;
  let message = err.message || 'Server error';

  if (err.type === 'entity.parse.failed') {
    status = 400;
    message = 'Request body is not valid JSON';
  } else if (err.code === 'ER_DUP_ENTRY') {
    status = 409;
    const field = (/for key '(?:\w+\.)?(\w+)'/.exec(err.sqlMessage) || [])[1] || '';
    message = `A record with this ${field.replace(/^uq_|_code$/g, '').replace(/_/g, ' ') || 'value'} already exists`;
  } else if (err.code === 'ER_ROW_IS_REFERENCED_2' || err.code === 'ER_ROW_IS_REFERENCED') {
    status = 409;
    message = 'This record is still referenced by other data';
  } else if (err.code === 'ER_NO_REFERENCED_ROW_2' || err.code === 'ER_NO_REFERENCED_ROW') {
    status = 400;
    message = 'A referenced record (category, shelf, member …) does not exist';
  } else if (err.code === 'ER_CHECK_CONSTRAINT_VIOLATED') {
    status = 400;
    message = 'The change violates a data rule of the library';
  } else if (err.code === 'ER_TRUNCATED_WRONG_VALUE' || err.code === 'ER_WRONG_VALUE') {
    status = 400;
    message = 'A date or number in the request is invalid';
  } else if (DB_UNREACHABLE.has(err.code)) {
    status = 503;
    message = 'The library database is unavailable right now. Please try again in a few minutes.';
  }

  if (status >= 500) console.error(err);
  const hide = status >= 500 && status !== 503 && process.env.NODE_ENV === 'production';
  res.status(status).json({ success: false, message: hide ? 'Server error' : message, details: err.details });
}

module.exports = { notFound, errorHandler };
