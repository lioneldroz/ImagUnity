-- Demande refusée par le responsable : gardée (masquée) pour ignorer les nouveaux envois de cette box, qui renvoie sa
-- demande tant qu'elle n'est pas enrôlée ; effacée après quelques semaines.
ALTER TABLE enrollment_request ADD COLUMN refused_at TIMESTAMP WITH TIME ZONE;
