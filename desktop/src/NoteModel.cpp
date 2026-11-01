#include "NoteModel.h"

NoteModel::NoteModel(QObject *parent)
    : QAbstractListModel(parent)
{
    // Placeholder data until the local database exists.
    m_notes = {
        {1, "Buy milk", false},
        {2, "Reply to landlord", false},
        {3, "Write the crypto spec", false},
        {4, "Set up Firebase emulators", true},
    };
}

int NoteModel::rowCount(const QModelIndex &parent) const
{
    return parent.isValid() ? 0 : m_notes.size();
}

QVariant NoteModel::data(const QModelIndex &index, int role) const
{
    if (!index.isValid() || index.row() >= m_notes.size())
        return {};

    const Note &note = m_notes.at(index.row());
    switch (role) {
    case IdRole:        return note.id;
    case TitleRole:     return note.title;
    case CompletedRole: return note.completed;
    }
    return {};
}

QHash<int, QByteArray> NoteModel::roleNames() const
{
    return {
        {IdRole, "noteId"},
        {TitleRole, "title"},
        {CompletedRole, "completed"},
    };
}
